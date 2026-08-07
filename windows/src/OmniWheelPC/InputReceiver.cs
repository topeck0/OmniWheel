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
    private int _minLatencySample = -1;

    // Single reusable state — updated in place
    public InputState CurrentState { get; private set; } = new();
    public bool IsConnected => _lastRemote != null && (DateTime.UtcNow - _lastInputTime).TotalMilliseconds < TimeoutMs;
    public string? ConnectedDeviceIp => _lastRemote?.Address.ToString();

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
        _task = Task.Run(RunAsync, _cts.Token);
        OnLog?.Invoke($"Input receiver listening on port {Protocol.InputPort} (aggressive mode, 512KB buf)");
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
                OnDisconnected?.Invoke();
                OnLog?.Invoke("Device disconnected (timeout)");
                _lastRemote = null;
                _lockedEndPoint = null;
                _ignoredOtherLogged = null;
                _minLatencySample = -1;
            }
            wasConnected = connected;
        }
    }

    private async Task RunAsync()
    {
        int packetCount = 0;
        int crcErrors = 0;
        int lastLogCount = 0;
        var lastLogTime = DateTime.UtcNow;

        while (_running && _cts != null && !_cts.IsCancellationRequested)
        {
            try
            {
                var result = await _udp!.ReceiveAsync(_cts.Token);
                var data = result.Buffer;
                var remote = result.RemoteEndPoint as IPEndPoint;

                // Multi-device protection: lock onto the first phone that talks
                // to us and ignore every other source so two phones can never
                // fight over the same connection and corrupt each other's input
                // or layout. The lock clears when the active phone times out.
                if (_lockedEndPoint == null)
                {
                    _lockedEndPoint = remote;
                }
                else if (remote != null && !_lockedEndPoint.Address.Equals(remote.Address))
                {
                    if (_ignoredOtherLogged != remote.Address.ToString())
                    {
                        _ignoredOtherLogged = remote.Address.ToString();
                        OnLog?.Invoke($"Ignoring packets from {remote.Address} — already connected to {_lockedEndPoint.Address}");
                    }
                    continue;
                }

                _lastRemote = remote;
                _lastInputTime = DateTime.UtcNow;
                packetCount++;

                if (!Protocol.TryParseHeader(data, 0, out var hdr))
                    continue;

                // Validate CRC
                if (!Protocol.ValidateCrc(data, hdr.HeaderSize))
                {
                    crcErrors++;
                    if (crcErrors <= 5)
                        OnLog?.Invoke($"CRC mismatch (#{crcErrors})");
                    continue;
                }

                if (hdr.Type == Protocol.PacketType.Connect)
                {
                    var ack = Protocol.BuildPacket(Protocol.PacketType.ConnectAck, hdr.Sequence);
                    await _udp.SendAsync(ack, ack.Length, result.RemoteEndPoint);
                    OnLog?.Invoke($"CONNECT from {_lastRemote?.Address}, sent ACK");
                }
                else if (hdr.Type == Protocol.PacketType.Input && hdr.PayloadLength >= 5)
                {
                    ParseInputInPlace(data, hdr.HeaderSize, hdr.PayloadLength, hdr.TimestampMs);
                    OnInputReceived?.Invoke();

                    // Periodic logging (every 5 seconds)
                    var now = DateTime.UtcNow;
                    if ((now - lastLogTime).TotalSeconds >= 5)
                    {
                        var delta = packetCount - lastLogCount;
                        OnLog?.Invoke($"Input: str={CurrentState.Steering} pk/s={delta / 5} crc_err={crcErrors}");
                        lastLogCount = packetCount;
                        lastLogTime = now;
                    }
                }
                else if (hdr.Type == Protocol.PacketType.Heartbeat)
                {
                    var ack = Protocol.BuildPacket(Protocol.PacketType.HeartbeatAck, hdr.Sequence);
                    await _udp.SendAsync(ack, ack.Length, result.RemoteEndPoint);
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
                        var key = _lastRemote?.Address.ToString() ?? "?";
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
                        if (st.ReceivedParts[part]) continue; // duplicate chunk
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
            catch (OperationCanceledException) { break; }
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
    private void ParseInputInPlace(byte[] data, int headerSize, int payloadLen, int headerTsMs)
    {
        int off = headerSize;

        // Real one-way latency. The phone sends its ms-within-second timestamp,
        // so reconstruct the full send time from our own wall clock, then nudge
        // it to the 60s cycle nearest "now". This avoids the mod-60000 wrap
        // boundary that used to produce wild spikes. The raw value still
        // includes any clock skew between the two devices, so the best
        // (minimum) sample seen is used as the baseline.
        long nowUnix = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long sendEstimate = (nowUnix / 60000) * 60000 + headerTsMs;
        if (sendEstimate > nowUnix + 30000) sendEstimate -= 60000;
        else if (sendEstimate < nowUnix - 30000) sendEstimate += 60000;
        int latency = (int)Math.Max(0, nowUnix - sendEstimate);
        if (_minLatencySample < 0 || latency < _minLatencySample) _minLatencySample = latency;
        CurrentState.LatencyMs = Math.Max(0, latency - _minLatencySample);

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
    public int LatencyMs { get; set; }

    public float NormalizedSteering => Steering / 32768f;
    public float NormalizedThrottle => Throttle / 255f;
    public float NormalizedBrake => Brake / 255f;
    public float NormalizedClutch => Clutch / 255f;
}