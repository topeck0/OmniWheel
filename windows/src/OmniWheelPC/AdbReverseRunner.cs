using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace OmniWheelPC.Network;

/// <summary>
/// Runs "adb reverse" so a phone connected via USB debugging can reach the
/// receiver over a localhost TCP port. adb reverse relays TCP only, so the
/// USB path wraps each OW datagram in a length-prefixed frame and the phone
/// dials 127.0.0.1:UsbBridgeTcpPort on its side.
///
/// While USB is enabled the port mapping is re-issued every couple seconds,
/// which makes the link recover automatically when the phone is unplugged and
/// plugged back in (adb reverse is not persistent across reconnects).
/// </summary>
public sealed class AdbReverseRunner : IDisposable
{
    private const string AdbDefaultCandidates = "adb;platform-tools\\adb.exe;adb.exe";

    private readonly Func<string> _adbPathResolver;
    private System.Threading.Timer? _cts;
    private readonly object _lock = new();
    private bool _enabled;
    private bool _disposed;
    private string? _lastError;

    public event Action<string>? OnLog;

    public AdbReverseRunner(Func<string>? adbPathResolver = null)
    {
        _adbPathResolver = adbPathResolver ?? (() => FindAdb());
    }

    public bool Enabled => _enabled;

    public static string FindAdb()
    {
        // Common locations: PATH, platform-tools in common SDK dirs, the
        // default location the user keeps it in.
        var candidates = new[]
        {
            "adb",
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "platform-tools", "adb.exe"),
            Path.Combine(Environment.CurrentDirectory, "platform-tools", "adb.exe"),
        };

        foreach (var candidate in candidates)
        {
            string? resolved = null;
            try
            {
                if (candidate.Contains(Path.DirectorySeparatorChar) || candidate.Contains('/'))
                {
                    if (File.Exists(candidate)) resolved = candidate;
                }
                else
                {
                    resolved = FindOnPath(candidate);
                }
            }
            catch { }
            if (resolved != null) return resolved;
        }
        return "adb"; // let Process.Start produce a clearer error message
    }

    private static string? FindOnPath(string name)
    {
        var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in pathEnv.Split(';'))
        {
            if (string.IsNullOrWhiteSpace(dir)) continue;
            try
            {
                var full = Path.Combine(dir.Trim(), name);
                if (File.Exists(full)) return full;
            }
            catch { }
        }
        return null;
    }

    public void Enable()
    {
        if (_disposed) return;
        lock (_lock)
        {
            if (_enabled) return;
            _enabled = true;
            _lastError = null;
            OnLog?.Invoke("USB enabled — setting up adb reverse on localhost:" + Protocol.UsbBridgeTcpPort);
        }
        _cts?.Dispose();
        _cts = new System.Threading.Timer(OnTick, null, 0, 3000);
        // Run immediately.
        OnTick(null);
    }

    public void Disable()
    {
        lock (_lock) _enabled = false;
        _cts?.Dispose();
        _cts = null;
        RunAdbReverse(remove: true);
        OnLog?.Invoke("USB disabled — adb reverse removed");
    }

    private void OnTick(object? state)
    {
        bool enabled;
        lock (_lock) enabled = _enabled;
        if (!enabled) return;
        RunAdbReverse(remove: false);
    }

    private void RunAdbReverse(bool remove)
    {
        var adb = _adbPathResolver();
        if (string.IsNullOrWhiteSpace(adb))
        {
            OnLog?.Invoke("adb not found — cannot run USB bridge");
            return;
        }

        var spec = "tcp:" + Protocol.UsbBridgeTcpPort;
        // Clean any stale mapping, then (re)apply the reverse.
        RunProcess(adb, "reverse --remove " + spec, ignoreFailure: true);
        if (remove) return;
        RunProcess(adb, "reverse " + spec + " " + spec, ignoreFailure: false);
        // Make sure at least one device is being watched — surfaced in log once.
        RunProcess(adb, "devices", ignoreFailure: true);
    }

    private void RunProcess(string adb, string args, bool ignoreFailure)
    {
        try
        {
            var psi = new ProcessStartInfo(adb, args)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var proc = Process.Start(psi);
            if (proc == null) return;
            string err = proc.StandardError.ReadToEnd().Trim();
            proc.WaitForExit(5000);
            if (!ignoreFailure && !string.IsNullOrEmpty(err) && err != _lastError)
            {
                _lastError = err;
                OnLog?.Invoke($"adb: {err}");
            }
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"Cannot run adb ({adb}): {ex.Message}");
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Disable();
    }
}