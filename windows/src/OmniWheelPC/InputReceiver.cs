using System.Net;
using System.Net.Sockets;

namespace OmniWheelPC.Network;

/// <summary>
/// Ultra-low-latency input receiver.
/// - Reuses a single InputState (no per-packet allocation)
/// - Supports v1 and v2 protocol headers
/// - Uses CRC lookup table
/// - Large socket buffer for burst absorption
/// - DontFragment + TTL=255 for aggressive delivery
/// </summary>
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

    private IPEndPoint? _lastRemote;
    private DateTime _lastInputTime;
    private const int TimeoutMs = 8000;

    // Single reusable state — updated in place
    public InputState CurrentState { get; private set; } = new();
    public bool IsConnected => _lastRemote != null && (DateTime.UtcNow - _lastInputTime).TotalMilliseconds < TimeoutMs;
    public string? ConnectedDeviceIp => _lastRemote?.Address.ToString();

    // Reusable receive buffer
    private byte[]? _recvBuf;

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
        
        _recvBuf = new byte[64];

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
                _lastRemote = result.RemoteEndPoint as IPEndPoint;
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
                    ParseInputInPlace(data, hdr.HeaderSize, hdr.PayloadLength);
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
    
    public DateTime Timestamp { get; set; }

    public float NormalizedSteering => Steering / 32768f;
    public float NormalizedThrottle => Throttle / 255f;
    public float NormalizedBrake => Brake / 255f;
    public float NormalizedClutch => Clutch / 255f;
}