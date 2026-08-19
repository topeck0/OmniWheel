using System.Net;
using System.Net.Sockets;
using System.Text;

namespace OmniWheelPC.Network;

/// <summary>
/// Ultra-low-latency input receiver.
/// - Reuses a single InputState (no per-packet allocation)
/// - Supports v1 and v2 protocol headers
/// - Uses CRC lookup table
/// - Large socket buffer for burst absorption
/// - DontFragment + TTL=255 for aggressive delivery
/// - Shared packet pipeline fed by two transports:
///     * WiFi/UDP on Protocol.InputPort
///     * USB debugging over adb reverse (TCP, length-prefixed frames)
/// </summary>
internal class ChunkState
{
    public int Total;
    public byte[] Buffer = Array.Empty<byte>();
    public int[] ChunkLens = Array.Empty<int>();
    public bool[] ReceivedParts = Array.Empty<bool>();
    public int Received;
}

public class InputReceiver : IDisposable
{
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private Task? _task;
    private bool _running;

    // USB debugging bridge (adb reverse). Carries each OW packet inside a
    // 4-byte big-endian length prefix plus payload, over one TCP connection.
    private TcpListener? _usbListener;
    private Task? _usbListenerTask;
    private TcpClient? _usbClient;
    private NetworkStream? _usbStream;
    private readonly SemaphoreSlim _usbWriteLock = new(1, 1);

    public event Action? OnInputReceived;
    public event Action<string>? OnLog;
    public event Action? OnConnected;
    public event Action? OnDisconnected;
    public event Action? OnMetaReceived;
    public event Action<string>? OnWidgetReceived;

    private IPEndPoint? _lastRemote;
    private IPEndPoint? _lockedEndPoint;
    private string? _ignoredOtherLogged;
    private DateTime _lastInputTime;
    private const int TimeoutMs = 8000;
    private byte _lastInputSeq;
    private bool _hasInputSeq;

    // Timestamp of the most recent PING we sent. A PONG carrying a DIFFERENT
    // timestamp is a stale/delayed echo from an older PING; accepting it would
    // measure the age of that old PING against NOW and fake a huge latency
    // spike (the sudden "400/600ms" even on a perfectly stable link).
    private long _lastSentPingTs = -1;
    private bool _sentPing;

    // Packet counters (shared between UDP and USB paths)
    private int _packetCount;
    private int _crcErrors;
    private int _lastLogCount;
    private DateTime _lastLogTime = DateTime.UtcNow;

    // Single reusable state — updated in place
    public InputState CurrentState { get; private set; } = new();
    public bool IsConnected => _lastRemote != null && (DateTime.UtcNow - _lastInputTime).TotalMilliseconds < TimeoutMs;
    public string? ConnectedDeviceIp => _lastRemote?.Address.ToString();

    // Current transport used by the connected phone: "wifi" (UDP) or "usb" (TCP over adb).
    public string CurrentTransport => _usbClient != null && _usbStream != null ? "usb" : "wifi";
    public bool IsUsbConnection => CurrentTransport == "usb";

    // Reusable receive buffer
    private readonly Dictionary<string, ChunkState> _chunkStates = new();

    public void Start()
    {
        if (_running) return;
        _running = true;
        _cts = new CancellationTokenSource();
        
        _udp = new UdpClient(Protocol.InputPort);
        _udp.Client.ReceiveBufferSize = 512 * 1024;  // 512KB receive buffer
        _udp.Client.DontFragment = true;
        _udp.Client.Ttl = 255;
        _udp.EnableBroadcast = true;

        _ = WatchdogAsync(_cts.Token);
        _ = PingLoopAsync(_cts.Token);
        _task = Task.Run(RunAsync, _cts.Token);
        OnLog?.Invoke($"Input receiver listening on port {Protocol.InputPort} (aggressive mode, 512KB buf)");

        // USB bridge: a phone that ran "adb reverse tcp:UsbBridgeTcpPort tcp:UsbBridgeTcpPort"
        // opens a TCP connection to localhost on this port. We exchange every OW
        // packet as a length-prefixed frame over it.
        _usbListener = new TcpListener(IPAddress.Loopback, Protocol.UsbBridgeTcpPort);
        try
        {
            _usbListener.Start();
            _usbListenerTask = Task.Run(UsbAcceptLoopAsync, _cts.Token);
            OnLog?.Invoke($"USB bridge listening on 127.0.0.1:{Protocol.UsbBridgeTcpPort} (adb reverse)");
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"USB bridge start failed: {ex.Message}");
        }
    }

    private async Task UsbAcceptLoopAsync()
    {
        while (_running && _cts != null && !_cts.IsCancellationRequested)
        {
            TcpClient? client = null;
            try
            {
                client = await _usbListener!.AcceptTcpClientAsync(_cts.Token);

                // Only one phone on the wire at a time — replace any previous
                // USB session.
                client.NoDelay = true; // low-latency frames, no Nagle batching
                Interlocked.Exchange(ref _usbClient, client)?.Dispose();
                var stream = client.GetStream();
                var oldStream = _usbStream;
                _usbStream = stream;
                _usbClient = client;
                OnLog?.Invoke("USB device connected over adb bridge");

                // Kick the shared connection state so watchdog reports connected.
                _lastRemote = new IPEndPoint(IPAddress.Loopback, Protocol.UsbBridgeTcpPort);
                _lockedEndPoint = _lastRemote;
                _lastInputTime = DateTime.UtcNow;
                _hasInputSeq = false;
                OnConnected?.Invoke();
                OnLog?.Invoke($"Device connected: USB (adb reverse)");

                _ = Task.Run(async () =>
                {
                    try
                    {
                        while (_cts != null && !_cts.IsCancellationRequested)
                        {
                            var frame = await ReadPacketFrameAsync(stream, _cts.Token);
                            if (frame == null) break;
                            await HandleOwPacketAsync(frame, _lastRemote!);
                        }
                    }
                    catch (Exception)
                    {
                        // Connection dropped — handle teardown below.
                    }
                    finally
                    {
                        if (ReferenceEquals(_usbStream, stream))
                        {
                            _usbStream = null;
                            _usbClient = null;
                            TryDispose(client);
                            if (_lastRemote?.Address != null && _lastRemote!.Address.Equals(IPAddress.Loopback))
                            {
                                _lastRemote = null;
                                _lockedEndPoint = null;
                            }
                            _hasInputSeq = false;
                            OnDisconnected?.Invoke();
                            OnLog?.Invoke("USB device disconnected");
                        }
                    }
                }, _cts.Token);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                if (_running) OnLog?.Invoke($"USB accept error: {ex.Message}");
            }
        }
    }

    private static void TryDispose(TcpClient client)
    {
        try { client.Dispose(); } catch { }
    }

    /// <summary>
    /// Forcefully close the current USB debugging session right now, without
    /// waiting for the remote to drop. Used when the user toggles USB off.
    /// Disposing the socket makes any blocking ReadAsync throw immediately,
    /// which unrolls the accept handler's reader task and runs its normal
    /// teardown (state clear + OnDisconnected), so the shared connection state
    /// always settles consistently.
    /// </summary>
    public void StopUsbSession()
    {
        var client = _usbClient;
        if (client != null) TryDispose(client);
    }

    /// <summary>
    /// Read one [uint32 big-endian length][payload] frame from the USB TCP
    /// stream. Returns null when the stream closes cleanly.
    /// </summary>
    private static async Task<byte[]?> ReadPacketFrameAsync(NetworkStream stream, CancellationToken ct)
    {
        byte[] lenBuf = new byte[4];
        int got = 0;
        while (got < 4)
        {
            int n = await stream.ReadAsync(lenBuf.AsMemory(got, 4 - got), ct);
            if (n <= 0) return null;
            got += n;
        }
        int len = (lenBuf[0] << 24) | (lenBuf[1] << 16) | (lenBuf[2] << 8) | lenBuf[3];
        if (len <= 0 || len > 65535) return null;
        var payload = new byte[len];
        got = 0;
        while (got < len)
        {
            int n = await stream.ReadAsync(payload.AsMemory(got, len - got), ct);
            if (n <= 0) return null;
            got += n;
        }
        return payload;
    }

    /// <summary>
    /// Send a reply using whichever transport is active: if a USB TCP session
    /// is up, that; otherwise the UDP socket to the given endpoint.
    /// </summary>
    private async Task SendReplyAsync(byte[] packet, IPEndPoint target)
    {
        var stream = _usbStream;
        if (stream != null)
        {
            try
            {
                await _usbWriteLock.WaitAsync();
                try
                {
                    var lb = BitConverter.GetBytes((uint)packet.Length);
                    Array.Reverse(lb); // big-endian
                    await stream.WriteAsync(lb, 0, 4);
                    await stream.WriteAsync(packet, 0, packet.Length);
                    await stream.FlushAsync();
                }
                finally { _usbWriteLock.Release(); }
            }
            catch { }
            return;
        }
        if (_udp != null)
        {
            try { await _udp.SendAsync(packet, packet.Length, target); }
            catch { }
        }
    }

    /// <summary>
    /// Shared packet handler. Works identically whether the packet arrived over
    /// UDP or the USB bridge; replies go back over whichever transport is live.
    /// </summary>
    private async Task HandleOwPacketAsync(byte[] data, IPEndPoint remote)
    {
        // Multi-device protection: lock onto the first phone that talks to us
        // and ignore every other source so two phones can never fight over the
        // same connection. The lock clears when the active phone times out.
        if (_lockedEndPoint == null)
        {
            _lockedEndPoint = remote;
        }
        else if (!_lockedEndPoint.Address.Equals(remote.Address))
        {
            if (_ignoredOtherLogged != remote.Address.ToString())
            {
                _ignoredOtherLogged = remote.Address.ToString();
                OnLog?.Invoke($"Ignoring packets from {remote.Address} — already connected to {_lockedEndPoint.Address}");
            }
            return;
        }

        _lastRemote = remote;
        _lastInputTime = DateTime.UtcNow;
        _packetCount++;

        if (!Protocol.TryParseHeader(data, 0, out var hdr))
            return;

        // Validate CRC
        if (!Protocol.ValidateCrc(data, hdr.HeaderSize))
        {
            _crcErrors++;
            if (_crcErrors <= 5)
                OnLog?.Invoke($"CRC mismatch (#{_crcErrors})");
            return;
        }

        if (hdr.Type == Protocol.PacketType.Connect)
        {
            var ack = Protocol.BuildPacket(Protocol.PacketType.ConnectAck, hdr.Sequence);
            await SendReplyAsync(ack, remote);
            OnLog?.Invoke($"CONNECT from {remote.Address}, sent ACK");
        }
        else if (hdr.Type == Protocol.PacketType.Input && hdr.PayloadLength >= 5)
        {
            // Reject stale/out-of-order replays (UDP can reorder): an old
            // packet arriving late would otherwise yank the wheel backward after
            // we already applied a newer value. Sequence is 0..127 (wraps at
            // 128); forward distance modulo 128, skip anything more than half a
            // cycle behind. Duplicates (distance 0) are fine.
            if (_hasInputSeq)
            {
                int dist = (hdr.Sequence - _lastInputSeq) & 0x7F;
                if (dist != 0 && dist >= 64) return;
            }
            _hasInputSeq = true;
            _lastInputSeq = hdr.Sequence;

            ParseInputInPlace(data, hdr.HeaderSize, hdr.PayloadLength);
            OnInputReceived?.Invoke();

            // Periodic logging (every 5 seconds)
            var now = DateTime.UtcNow;
            if ((now - _lastLogTime).TotalSeconds >= 5)
            {
                var delta = _packetCount - _lastLogCount;
                OnLog?.Invoke($"Input: str={CurrentState.Steering} pk/s={delta / 5} crc_err={_crcErrors}");
                _lastLogCount = _packetCount;
                _lastLogTime = now;
            }
        }
        else if (hdr.Type == Protocol.PacketType.Heartbeat)
        {
            var ack = Protocol.BuildPacket(Protocol.PacketType.HeartbeatAck, hdr.Sequence);
            await SendReplyAsync(ack, remote);
        }
        else if (hdr.Type == Protocol.PacketType.Pong && hdr.PayloadLength >= 4)
        {
            // RTT = now - echoed send time. The phone echoes our 4-byte
            // timestamp untouched, so this is clock-skew proof.
            long tsLow = (uint)(data[hdr.HeaderSize]
                | (data[hdr.HeaderSize + 1] << 8)
                | (data[hdr.HeaderSize + 2] << 16)
                | (data[hdr.HeaderSize + 3] << 24));

            // Reject stale echoes: only the PONG that echoes the timestamp of
            // the PING we MOST RECENTLY sent is the round-trip we care about.
            // A delayed PONG from an older PING would otherwise look like a
            // multi-hundred-ms latency out of nowhere on an otherwise stable
            // connection (WiFi queue hiccup, USB bridge blip, GC pause...).
            if (!_sentPing || tsLow != _lastSentPingTs) return;

            long nowUnix = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            long tsFull = (nowUnix & ~0xFFFFFFFFL) | tsLow;
            long rtt = nowUnix - tsFull;
            if (rtt < 0) rtt += 0x100000000L; // borrow across the 32-bit wrap
            // One-way latency = RTT/2. Kept as a double so sub-millisecond
            // values surface as e.g. "0.98 ms" instead of truncating to "0".
            CurrentState.LatencyMs = Math.Max(0, rtt / 2.0);
        }
        else if (hdr.Type == Protocol.PacketType.Meta && hdr.PayloadLength >= 12)
        {
            ParseMeta(data, hdr.HeaderSize, hdr.PayloadLength);
            OnMetaReceived?.Invoke();
        }
        else if (hdr.Type == Protocol.PacketType.HudWidget && hdr.PayloadLength > 0)
        {
            var json = Encoding.UTF8.GetString(data, hdr.HeaderSize, hdr.PayloadLength);
            OnWidgetReceived?.Invoke(json);
        }
        else if (hdr.Type == Protocol.PacketType.HudFull && hdr.PayloadLength >= 3)
        {
            int part = data[hdr.HeaderSize];
            int total = data[hdr.HeaderSize + 1];
            if (total > 0 && total <= 32 && part < total)
            {
                var key = remote.Address.ToString() ?? "?";
                if (!_chunkStates.TryGetValue(key, out var st) || st.Total != total)
                {
                    st = new ChunkState
                    {
                        Total = total,
                        Buffer = new byte[(long)total * Protocol.HudChunkDataSize],
                        ChunkLens = new int[total],
                        ReceivedParts = new bool[total]
                    };
                    _chunkStates[key] = st;
                    OnLog?.Invoke($"HUD_FULL burst start ({total} chunks)");
                }
                if (st.ReceivedParts[part]) return; // duplicate chunk
                int dataLen = hdr.PayloadLength - 2;
                Array.Copy(data, hdr.HeaderSize + 2, st.Buffer, part * Protocol.HudChunkDataSize, dataLen);
                st.ChunkLens[part] = dataLen;
                st.ReceivedParts[part] = true;
                st.Received++;
                if (st.Received == total)
                {
                    int totalLen = 0;
                    foreach (int len in st.ChunkLens) totalLen += len;
                    var exactBuf = new byte[totalLen];
                    int destOffset = 0;
                    for (int i = 0; i < total; i++)
                    {
                        Array.Copy(st.Buffer, i * Protocol.HudChunkDataSize, exactBuf, destOffset, st.ChunkLens[i]);
                        destOffset += st.ChunkLens[i];
                    }
                    var json = Encoding.UTF8.GetString(exactBuf);
                    _chunkStates.Remove(key);
                    OnLog?.Invoke($"HUD_FULL complete ({total} chunks, {totalLen} bytes, {json.Length} chars)");
                    OnWidgetReceived?.Invoke("FULL:" + json);
                }
            }
        }
    }

    private async Task WatchdogAsync(CancellationToken ct)
    {
        bool wasConnected = false;
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(500, ct);
            var connected = IsConnected;
            if (connected && !wasConnected)
            {
                OnConnected?.Invoke();
                OnLog?.Invoke($"Device connected: {ConnectedDeviceIp}");
            }
            else if (!connected && wasConnected)
            {
                // A USB session handles its own teardown notifications; only
                // clear UDP state when the phone actually timed out.
                if (!IsUsbConnection)
                {
                    OnDisconnected?.Invoke();
                    OnLog?.Invoke("Device disconnected (timeout)");
                    _lastRemote = null;
                    _lockedEndPoint = null;
                    _ignoredOtherLogged = null;
                    _hasInputSeq = false;
                }
            }
            wasConnected = connected;
        }
    }

    /// <summary>
    /// Send a timestamped PING to the connected phone every 500ms. The phone
    /// echoes it back as a PONG with the timestamp untouched, so we can compute
    /// a real, clock-skew-proof RTT latency (see the Pong handler).
    /// </summary>
    private async Task PingLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await Task.Delay(500, ct);
            var target = _lastRemote;
            if (target == null || !IsConnected) continue;
            try
            {
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                _lastSentPingTs = nowMs & 0xFFFFFFFFL; // record BEFORE we send
                _sentPing = true;
                var payload = new byte[4];
                payload[0] = (byte)(nowMs & 0xFF);
                payload[1] = (byte)((nowMs >> 8) & 0xFF);
                payload[2] = (byte)((nowMs >> 16) & 0xFF);
                payload[3] = (byte)((nowMs >> 24) & 0xFF);
                var pkt = Protocol.BuildPacket(Protocol.PacketType.Ping, 0, payload);
                await SendReplyAsync(pkt, target);
            }
            catch { }
        }
    }

    /// <summary>
    /// Ask the connected phone to re-send its meta + full HUD layout right now.
    /// Used when the receiver starts up (or reconnects) so the preview is never
    /// left empty waiting for the phone's next periodic sync.
    /// </summary>
    public async void RequestLayoutSync()
    {
        var target = _lastRemote ?? _lockedEndPoint;
        if (target == null || !IsConnected) return;
        try
        {
            var pkt = Protocol.BuildPacket(Protocol.PacketType.Connect, 0);
            await SendReplyAsync(pkt, target);
            OnLog?.Invoke($"Requested layout resync from {target.Address}");
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"Resync request failed: {ex.Message}");
        }
    }

    private async Task RunAsync()
    {
        while (_running && _cts != null && !_cts.IsCancellationRequested)
        {
            try
            {
                var result = await _udp!.ReceiveAsync(_cts.Token);
                var data = result.Buffer;
                var remote = result.RemoteEndPoint as IPEndPoint;
                if (remote == null) continue;
                // Delegate everything to the shared pipeline. Suppress re-entry
                // while packets are in flight.
                await HandleOwPacketAsync(data, remote);
            }
            catch (OperationCanceledException) { break; }
            catch (SocketException) { if (!_running) break; }
            catch (Exception ex)
            {
                if (_running) OnLog?.Invoke($"Input error: {ex.Message}");
            }
        }
    }

    /// <summary>
    /// Parse input payload and update CurrentState IN PLACE (zero allocation).
    /// Supports both v1 and v2 payload formats.
    /// </summary>
    private void ParseInputInPlace(byte[] data, int headerSize, int payloadLen)
    {
        int off = headerSize;

        // Core inputs (always present)
        CurrentState.Steering = (short)(data[off] | (data[off + 1] << 8));
        CurrentState.Throttle = data[off + 2];
        CurrentState.Brake = data[off + 3];
        CurrentState.Clutch = data[off + 4];

        if (payloadLen >= 13)
        {
            CurrentState.GyroX = (short)(data[off + 5] | (data[off + 6] << 8));
            CurrentState.GyroY = (short)(data[off + 7] | (data[off + 8] << 8));
            CurrentState.GyroZ = (short)(data[off + 9] | (data[off + 10] << 8));
            ParseButtons(data, off + 11, Math.Min(3, payloadLen - 11));
        }
        else if (payloadLen >= 7)
        {
            CurrentState.GyroX = 0;
            CurrentState.GyroY = 0;
            CurrentState.GyroZ = 0;
            ParseButtons(data, off + 5, Math.Min(3, payloadLen - 5));
        }
        else
        {
            CurrentState.GyroX = (short)(data[off + 5] | (data[off + 6] << 8));
            CurrentState.GyroY = (short)(data[off + 7] | (data[off + 8] << 8));
            CurrentState.GyroZ = (short)(data[off + 9] | (data[off + 10] << 8));
            ParseButtons(data, off + 11, Math.Min(3, payloadLen - 11));
        }

        CurrentState.Timestamp = DateTime.UtcNow;
    }

    /// <summary>
    /// Parse metadata payload (battery, steering range, screen size, flags, device name).
    ///   [0] battery % (0-100, 255 = unknown)
    ///   [1-2] steering max angle uint16 LE
    ///   [3-6] screen width px uint32 LE
    ///   [7-10] screen height px uint32 LE
    ///   [11] flags (bit0 = clutch enabled)
    ///   [12+] device type name UTF8 (optional)
    /// </summary>
    private void ParseMeta(byte[] data, int offset, int payloadLen)
    {
        int off = offset;
        int battery = data[off];
        int maxAngle = (ushort)(data[off + 1] | (data[off + 2] << 8));
        int w = (int)((uint)data[off + 3] | ((uint)data[off + 4] << 8) |
                      ((uint)data[off + 5] << 16) | ((uint)data[off + 6] << 24));
        int h = (int)((uint)data[off + 7] | ((uint)data[off + 8] << 8) |
                      ((uint)data[off + 9] << 16) | ((uint)data[off + 10] << 24));
        int flags = data[off + 11];

        CurrentState.PhoneBatteryPercent = battery;
        CurrentState.PhoneMaxAngle = maxAngle;
        CurrentState.PhoneScreenWidthPx = w;
        CurrentState.PhoneScreenHeightPx = h;
        CurrentState.ClutchEnabled = (flags & 0x01) != 0;

        if (payloadLen > 12)
            CurrentState.PhoneDeviceName = Encoding.UTF8.GetString(data, off + 12, payloadLen - 12).Trim();
    }

    /// <summary>
    /// Parse button bitmap into the fixed ButtonStates array.
    /// </summary>
    private void ParseButtons(byte[] data, int offset, int length)
    {
        Array.Clear(CurrentState.ButtonStates, 0, CurrentState.ButtonStates.Length);
        
        for (int i = 0; i < length && i < 3; i++)
        {
            byte b = data[offset + i];
            for (int bit = 0; bit < 8; bit++)
            {
                if ((b & (1 << bit)) != 0)
                {
                    int btnNum = i * 8 + bit + 1;
                    if (btnNum <= 24) CurrentState.ButtonStates[btnNum - 1] = true;
                }
            }
        }
    }

    public void Dispose()
    {
        _running = false;
        _cts?.Cancel();
        try { _usbListener?.Stop(); } catch { }
        try { _usbClient?.Dispose(); } catch { }
        _udp?.Dispose();
        _cts?.Dispose();
    }
}

/// <summary>
/// Optimized input state with fixed-size arrays (zero per-packet allocation).
/// </summary>
public class InputState
{
    public short Steering { get; set; }
    public byte Throttle { get; set; }
    public byte Brake { get; set; }
    public byte Clutch { get; set; }
    public short GyroX { get; set; }
    public short GyroY { get; set; }
    public short GyroZ { get; set; }
    
    public bool[] ButtonStates { get; } = new bool[24];
    public bool[] Buttons => ButtonStates;

    // Phone metadata (received via Meta packets)
    public int PhoneBatteryPercent { get; set; } = 255;   // 255 = unknown
    public int PhoneMaxAngle { get; set; } = 900;
    public int PhoneScreenWidthPx { get; set; }
    public int PhoneScreenHeightPx { get; set; }
    public string PhoneDeviceName { get; set; } = "";
    public bool ClutchEnabled { get; set; } = true;

    public DateTime Timestamp { get; set; }
    /// <summary>One-way latency in ms. Stored as a double so sub-1ms values
    /// (e.g. rtt 1.9ms -> 0.95ms) display as "0.95 ms" instead of truncating
    /// the impossible-looking "0 ms".</summary>
    public double LatencyMs { get; set; }

    public float NormalizedSteering => Steering / 32768f;
    public float NormalizedThrottle => Throttle / 255f;
    public float NormalizedBrake => Brake / 255f;
    public float NormalizedClutch => Clutch / 255f;
}