using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace OmniWheelPC.Network;

/// <summary>
/// Runs "adb reverse" so a phone connected via USB can reach the
/// receiver over a localhost TCP port. adb reverse relays TCP only, so the
/// USB path wraps each OW datagram in a length-prefixed frame and the phone
/// dials 127.0.0.1:UsbBridgeTcpPort on its side.
///
/// CRITICAL: we must NEVER run "adb reverse --remove" while a phone session
/// is live — removing the mapping kills the established forwarded connection
/// (the "broken pipe" + "USB device disconnected" after ~3s bug). The mapping
/// is only removed on an explicit disable. While enabled we re-issue the
/// reverse periodically, but only when no USB session is currently up, so
/// unplug/replug recovery still works without tearing down an active link.
/// </summary>
public sealed class AdbReverseRunner : IDisposable
{
    private const string AdbDefaultCandidates = "adb;platform-tools\\adb.exe;adb.exe";

    private readonly Func<string> _adbPathResolver;
    private readonly Func<bool>? _isUsbSessionActive;
    private System.Threading.Timer? _timer;
    private readonly object _lock = new();
    private bool _enabled;
    private bool _disposed;
    private string? _lastError;

    public event Action<string>? OnLog;

    /// <summary>
    /// isUsbSessionActive should return true while the receiver currently has
    /// a connected USB device — while true the mapping is left completely
    /// untouched (no re-issue, no removal) so the tunnel never gets severed.
    /// </summary>
    public AdbReverseRunner(Func<string>? adbPathResolver = null, Func<bool>? isUsbSessionActive = null)
    {
        _adbPathResolver = adbPathResolver ?? (() => FindAdb());
        _isUsbSessionActive = isUsbSessionActive;
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
        _timer?.Dispose();
        _timer = new System.Threading.Timer(OnTick, null, 0, 3000);
        // Run immediately.
        OnTick(null);
    }

    public void Disable()
    {
        lock (_lock) _enabled = false;
        _timer?.Dispose();
        _timer = null;
        RunAdbReverse(remove: true);
        OnLog?.Invoke("USB disabled — adb reverse removed");
    }

    private void OnTick(object? state)
    {
        bool enabled;
        lock (_lock) enabled = _enabled;
        if (!enabled) return;

        // A live phone session owns the tunnel: touching the mapping here would
        // sever the connection. Wait until the phone is gone to re-issue.
        if (_isUsbSessionActive?.Invoke() == true) return;

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
        if (remove)
        {
            // Explicit disable (or shutdown) only. Never in the tick loop.
            RunProcess(adb, "reverse --remove " + spec, ignoreFailure: true);
            return;
        }

        // (Re)apply without removing first: if the mapping already exists this
        // is a harmless no-op; once a phone unplugs and replugs, the next tick
        // after the session drops will recreate it.
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