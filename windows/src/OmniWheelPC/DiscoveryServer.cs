using System.Net;
using System.Net.Sockets;
using System.Text;

namespace OmniWheelPC.Network;

/// <summary>
/// Optimized discovery server.
/// - Supports v1 and v2 protocol headers
/// - Reduced logging noise
/// - Reuses response buffer
/// </summary>
public class DiscoveryServer : IDisposable
{
    private UdpClient? _udp;
    private CancellationTokenSource? _cts;
    private CancellationTokenSource? _announceCts;
    private Task? _task;
    private Task? _announceTask;
    private bool _running;

    public string DeviceName { get; set; } = Environment.MachineName;
    public int DiscoveredCount => _discoveredDevices.Count;
    public event Action<DiscoveredDevice>? OnDeviceFound;
    public event Action? OnStarted;
    public event Action<string>? OnLog;

    private readonly List<DiscoveredDevice> _discoveredDevices = new();
    private readonly object _lock = new();
    private byte[]? _cachedResponse;
    private string _cachedName = "";

    public void Start()
    {
        if (_running) return;
        _running = true;
        _cts = new CancellationTokenSource();
        _udp = new UdpClient(Protocol.DiscoveryPort);
        _udp.EnableBroadcast = true;

        _task = Task.Run(RunAsync, _cts.Token);

        // Proactively announce this PC over broadcast every 2s. Even if a
        // phone's discovery broadcast gets dropped by the router, the phone's
        // listener still picks the PC up from this unsolicited response.
        _announceCts = new CancellationTokenSource();
        var announceCt = _announceCts.Token;
        var broadcastEp = new IPEndPoint(IPAddress.Broadcast, Protocol.DiscoveryPort);
        _announceTask = Task.Run(async () =>
        {
            while (!announceCt.IsCancellationRequested)
            {
                try
                {
                    SendDiscoveryResponse(broadcastEp);
                }
                catch { }
                await Task.Delay(2000, announceCt);
            }
        }, announceCt);

        OnLog?.Invoke($"Discovery server on port {Protocol.DiscoveryPort} (announcing every 2s)");
        OnStarted?.Invoke();
    }

    private async Task RunAsync()
    {
        while (_running && _cts != null && !_cts.IsCancellationRequested)
        {
            try
            {
                var result = await _udp!.ReceiveAsync(_cts.Token);
                var data = result.Buffer;

                if (!Protocol.TryParseHeader(data, 0, out var hdr))
                    continue;

                if (hdr.Type == Protocol.PacketType.Discover)
                {
                    // SECURITY: payload length is untrusted — bound it to what
                    // actually arrived before reading.
                    int maxPayload = data.Length - hdr.HeaderSize - Protocol.CrcSize;
                    string? deviceName = null;
                    if (hdr.PayloadLength > 0 && hdr.PayloadLength <= maxPayload)
                        deviceName = Encoding.UTF8.GetString(data, hdr.HeaderSize, hdr.PayloadLength);

                    var device = new DiscoveredDevice
                    {
                        IpAddress = result.RemoteEndPoint.Address.ToString(),
                        Port = result.RemoteEndPoint.Port,
                        DeviceName = deviceName ?? "Unknown",
                        LastSeen = DateTime.UtcNow
                    };

                    bool isNew = false;
                    lock (_lock)
                    {
                        var existing = _discoveredDevices.FirstOrDefault(d => d.IpAddress == device.IpAddress);
                        if (existing != null)
                        {
                            existing.DeviceName = device.DeviceName;
                            existing.LastSeen = device.LastSeen;
                        }
                        else
                        {
                            _discoveredDevices.Add(device);
                            isNew = true;
                        }
                    }

                    if (isNew)
                    {
                        OnDeviceFound?.Invoke(device);
                        OnLog?.Invoke($"Found: {device.DeviceName} @ {device.IpAddress}");
                    }

                    // Send response (cached)
                    SendDiscoveryResponse(result.RemoteEndPoint);
                }
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                if (_running) OnLog?.Invoke($"Discovery error: {ex.Message}");
            }
        }
    }

    private void SendDiscoveryResponse(IPEndPoint target)
    {
        // Cache response for same device name
        if (_cachedName != DeviceName)
        {
            var payload = Encoding.UTF8.GetBytes(DeviceName);
            _cachedResponse = Protocol.BuildPacket(Protocol.PacketType.DiscoverResponse, 0, payload);
            _cachedName = DeviceName;
        }
        if (_cachedResponse != null)
        {
            _udp?.SendAsync(_cachedResponse, _cachedResponse.Length, target);
        }
    }

    public List<DiscoveredDevice> GetDiscoveredDevices()
    {
        lock (_lock)
            return _discoveredDevices.Where(d => (DateTime.UtcNow - d.LastSeen).TotalSeconds < 15).ToList();
    }

    public void Dispose()
    {
        _running = false;
        _announceCts?.Cancel();
        _cts?.Cancel();
        _udp?.Dispose();
        _announceCts?.Dispose();
        _cts?.Dispose();
    }
}

public class DiscoveredDevice
{
    public string IpAddress { get; set; } = "";
    public int Port { get; set; }
    public string DeviceName { get; set; } = "";
    public DateTime LastSeen { get; set; }
}