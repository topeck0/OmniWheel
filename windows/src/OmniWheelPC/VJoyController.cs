using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;

namespace OmniWheelPC.Network;

/// <summary>
/// Optimized vJoy controller with dirty tracking.
/// Only calls P/Invoke SetAxis/SetBtn when values actually change.
/// Reduces P/Invoke calls from ~132/1ms to typically 1-5/1ms.
///
/// A dedicated output loop (StartOutputLoop) drives Update at a fixed 250Hz
/// regardless of packet arrival, and the steering axis is eased toward the
/// latest received value with a time-based exponential filter. This is what
/// makes the wheel move smoothly IN-GAME: the phone's touch events and WiFi
/// jitter produce discrete steering steps (e.g. 0 -> 24 -> 56 -> 100), and
/// writing them verbatim made the game jump between them. The output loop
/// interpolates so every intermediate value reaches the game, while adding
/// only ~30ms of imperceptible inertia. Pedals/buttons stay near-instant
/// (much smaller time constant).
/// </summary>
public class VJoyController : IDisposable
{
    // Resolve vJoyInterface.dll from known install paths
    // (required because PublishSingleFile extracts EXE to temp dir,
    //  and vJoy DLL is not in the standard DLL search path)
    static VJoyController()
    {
        NativeLibrary.SetDllImportResolver(Assembly.GetExecutingAssembly(), (libraryName, assembly, searchPath) =>
        {
            if (libraryName != "vJoyInterface.dll") return IntPtr.Zero;

            // Known vJoy install locations (x64)
            var candidates = new[]
            {
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles) + @"\vJoy\x64\vJoyInterface.dll",
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86) + @"\vJoy\x64\vJoyInterface.dll",
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles) + @"\vJoy\x86\vJoyInterface.dll",
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86) + @"\vJoy\x86\vJoyInterface.dll",
                // Some installers put it in System32/SysWOW64
                Environment.GetFolderPath(Environment.SpecialFolder.System) + @"\vJoyInterface.dll",
            };

            foreach (var path in candidates)
            {
                if (NativeLibrary.TryLoad(path, out var handle))
                {
                    return handle;
                }
            }

            // Fall back to default search
            return NativeLibrary.TryLoad(libraryName, assembly, searchPath, out var h) ? h : IntPtr.Zero;
        });
    }

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern bool vJoyEnabled();

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern uint GetvJoyVersion();

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern int AcquireVJD(uint id);

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern void RelinquishVJD(uint id);

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern bool ResetVJD(uint id);

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern int SetAxis(long value, uint id, HID_USAGES axis);

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern int SetBtn(bool state, uint id, byte button);

    [DllImport("vJoyInterface.dll", CallingConvention = CallingConvention.StdCall)]
    private static extern int GetVJDStatus(uint id);

    private enum HID_USAGES : uint
    {
        HID_USAGE_X = 0x30,
        HID_USAGE_Y = 0x31,
        HID_USAGE_Z = 0x32,
        HID_USAGE_RX = 0x33,
    }

    [Flags]
    private enum VjdStat : int
    {
        VJD_STAT_OWN = 0,
        VJD_STAT_FREE = 1,
        VJD_STAT_BUSY = 2,
        VJD_STAT_MISS = 3,
    }

    private const uint VJD_ID = 1;
    private const long AXIS_MIN = 0;
    private const long AXIS_MAX = 32767;
    private const long AXIS_CENTER = 16383;

    private bool _acquired = false;
    private bool _vJoyInstalled = false;
    private string _initError = "";
    public string InitError => _initError;

    // Dirty tracking: only update when values change
    private long _prevSteering = long.MinValue;
    private long _prevThrottle = long.MinValue;
    private long _prevBrake = long.MinValue;
    private long _prevClutch = long.MinValue;
    private bool[] _prevButtons = new bool[24];
    private bool _buttonsInitialized = false;

    // ===== Output loop + smoothing state (single writer thread only) =====
    private Thread? _outputThread;
    private volatile bool _outputRunning;
    private volatile bool _zeroPending;
    private InputState? _outputState;
    private Func<bool>? _isConnected;
    private readonly InputState _neutral = new();
    private readonly Stopwatch _outputClock = Stopwatch.StartNew();
    private long _lastOutputTick;
    // Shown (eased) values in INPUT units — steering -32768..32767, axes 0..255
    private double _shownSteering;
    private double _shownThrottle;
    private double _shownBrake;
    private double _shownClutch;

    /// <summary>Time constant (ms) of the steering exponential ease.
    /// ~30ms reads as instant to a driver but erases packet-rate steps.</summary>
    public double SteeringSmoothingMs { get; set; } = 30.0;
    /// <summary>Time constant (ms) for throttle/brake/clutch easing.</summary>
    public double PedalSmoothingMs { get; set; } = 8.0;

    /// <summary>
    /// Start the fixed-rate output loop. From now on vJoy is written at 250Hz
    /// from this thread only, eased toward the latest received input — never
    /// call Update/ZeroOutputs from other threads while the loop runs.
    /// When isConnected returns false the loop drives neutral instead of the
    /// (stale) last state, so a dropped link always settles at center/zero.
    /// </summary>
    public void StartOutputLoop(InputState state, Func<bool>? isConnected = null)
    {
        if (_outputRunning) return;
        _outputState = state;
        _isConnected = isConnected;
        _outputRunning = true;
        _lastOutputTick = _outputClock.ElapsedMilliseconds;
        _shownSteering = state.Steering;
        _shownThrottle = state.Throttle;
        _shownBrake = state.Brake;
        _shownClutch = state.Clutch;
        _outputThread = new Thread(OutputLoop) { Name = "VJoyOutput", IsBackground = true, Priority = ThreadPriority.AboveNormal };
        _outputThread.Start();
        OnLog?.Invoke("vJoy output loop started (250Hz smoothed)");
    }

    public void StopOutputLoop()
    {
        _outputRunning = false;
        if (_outputThread != null && _outputThread != Thread.CurrentThread)
        {
            _outputThread.Join(500);
            _outputThread = null;
        }
    }

    /// <summary>
    /// Ease every axis and button back to neutral immediately (output-loop
    /// thread only — request it via RequestZero from anywhere).
    /// </summary>
    public void ZeroOutputs()
    {
        SetAxisSafe(AXIS_CENTER, AXIS_CENTER, ref _prevSteering, HID_USAGES.HID_USAGE_X);
        SetAxisSafe(0, 0, ref _prevThrottle, HID_USAGES.HID_USAGE_Y);
        SetAxisSafe(0, 0, ref _prevBrake, HID_USAGES.HID_USAGE_Z);
        SetAxisSafe(0, 0, ref _prevClutch, HID_USAGES.HID_USAGE_RX);
        for (int i = 0; i < 24; i++)
        {
            if (!_buttonsInitialized || _prevButtons[i])
            {
                SetBtn(false, VJD_ID, (byte)(i + 1));
                _prevButtons[i] = false;
            }
        }
        _buttonsInitialized = true;
        _shownSteering = 0;
        _shownThrottle = 0;
        _shownBrake = 0;
        _shownClutch = 0;
    }

    private void SetAxisSafe(long value, long prev, ref long prevField, HID_USAGES axis)
    {
        if (value != prev || prev == long.MinValue)
        {
            SetAxis(value, VJD_ID, axis);
            prevField = value;
        }
    }

    private void OutputLoop()
    {
        while (_outputRunning)
        {
            try
            {
                if (_zeroPending)
                {
                    _zeroPending = false;
                    ZeroOutputs();
                }
                var state = _outputState;
                if (state == null) { Thread.Sleep(4); continue; }
                // Link down: drive toward neutral, never the stale last input
                bool live = _isConnected?.Invoke() ?? true;
                Update(live ? state : _neutral);
            }
            catch { }
            // 250Hz fixed cadence
            Thread.Sleep(4);
        }
    }

    /// <summary>Thread-safe request: the next output tick releases every axis
    /// and button to neutral (used on device disconnect).</summary>
    public void RequestZero()
    {
        _zeroPending = true;
    }

    // Stats
    private int _totalCalls;
    private int _skippedCalls;

    public event Action<string>? OnLog;
    public bool IsAvailable => _vJoyInstalled && _acquired;

    public bool Initialize()
    {
        try
        {
            if (!vJoyEnabled())
            {
                _initError = "vJoy driver not enabled";
                OnLog?.Invoke("vJoy is installed but driver is not enabled — open vJoy app and enable it");
                return false;
            }
            _vJoyInstalled = true;
            var version = GetvJoyVersion();
            var major = (version >> 16) & 0xFFFF;
            var minor = version & 0xFFFF;
            OnLog?.Invoke($"vJoy v{major}.{minor} detected");

            var status = (VjdStat)GetVJDStatus(VJD_ID);
            OnLog?.Invoke($"vJoy device 1 status: {status}");

            var result = AcquireVJD(VJD_ID);
            if (result == 0)
            {
                _initError = "Device busy or missing";
                OnLog?.Invoke("Failed to acquire vJoy device 1 — is another app using it, or does the device not exist in vJoy config?");
                return false;
            }
            ResetVJD(VJD_ID);
            _acquired = true;
            OnLog?.Invoke("vJoy device 1 acquired (dirty-tracking optimized)");
            return true;
        }
        catch (DllNotFoundException)
        {
            _initError = "DLL not found";
            OnLog?.Invoke("vJoyInterface.dll not found — install vJoy from sourceforge.net/projects/vjoy");
            return false;
        }
        catch (BadImageFormatException)
        {
            _initError = "Wrong architecture (x86/x64 mismatch)";
            OnLog?.Invoke("vJoy DLL architecture mismatch — ensure vJoy x64 is installed for 64-bit app");
            return false;
        }
        catch (Exception ex)
        {
            _initError = ex.Message;
            OnLog?.Invoke($"vJoy init error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// Advance the eased output values toward the latest input targets and
    /// write them to vJoy (dirty-tracked). Called at a fixed 250Hz by the
    /// output loop, so the game receives continuous intermediate axis values
    /// even when packets arrive in bursts — that is what removes the visible
    /// 0/24/56/100 stepping inside games.
    /// </summary>
    public void Update(InputState state)
    {
        if (!_acquired) return;

        // Time-based exponential ease: identical smoothing regardless of the
        // actual tick interval (Sleep jitter, GC pauses...).
        long now = _outputClock.ElapsedMilliseconds;
        double dtMs = now - _lastOutputTick;
        if (dtMs <= 0) dtMs = 4;
        if (dtMs > 200) dtMs = 200; // after a stall, converge fast but bounded
        _lastOutputTick = now;

        double steerAlpha = 1.0 - Math.Exp(-dtMs / Math.Max(1.0, SteeringSmoothingMs));
        double pedalAlpha = 1.0 - Math.Exp(-dtMs / Math.Max(1.0, PedalSmoothingMs));

        // Steering: ease toward target; snap when close so full lock stays exact
        double steerTarget = state.Steering;
        _shownSteering += (steerTarget - _shownSteering) * steerAlpha;
        if (Math.Abs(steerTarget - _shownSteering) < 8.0) _shownSteering = steerTarget;

        long steeringAxis = AXIS_CENTER + ((long)Math.Round(_shownSteering) / 2);
        if (steeringAxis < AXIS_MIN) steeringAxis = AXIS_MIN;
        else if (steeringAxis > AXIS_MAX) steeringAxis = AXIS_MAX;
        if (steeringAxis != _prevSteering)
        {
            SetAxis(steeringAxis, VJD_ID, HID_USAGES.HID_USAGE_X);
            _prevSteering = steeringAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Throttle
        _shownThrottle += (state.Throttle - _shownThrottle) * pedalAlpha;
        if (Math.Abs(state.Throttle - _shownThrottle) < 2.0) _shownThrottle = state.Throttle;
        long throttleAxis = (long)((_shownThrottle / 255.0) * AXIS_MAX);
        if (throttleAxis != _prevThrottle)
        {
            SetAxis(throttleAxis, VJD_ID, HID_USAGES.HID_USAGE_Y);
            _prevThrottle = throttleAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Brake
        _shownBrake += (state.Brake - _shownBrake) * pedalAlpha;
        if (Math.Abs(state.Brake - _shownBrake) < 2.0) _shownBrake = state.Brake;
        long brakeAxis = (long)((_shownBrake / 255.0) * AXIS_MAX);
        if (brakeAxis != _prevBrake)
        {
            SetAxis(brakeAxis, VJD_ID, HID_USAGES.HID_USAGE_Z);
            _prevBrake = brakeAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Clutch (0% at rest, 100% fully pressed — matches the preview display)
        _shownClutch += (state.Clutch - _shownClutch) * pedalAlpha;
        if (Math.Abs(state.Clutch - _shownClutch) < 2.0) _shownClutch = state.Clutch;
        long clutchAxis = (long)((_shownClutch / 255.0) * AXIS_MAX);
        if (clutchAxis != _prevClutch)
        {
            SetAxis(clutchAxis, VJD_ID, HID_USAGES.HID_USAGE_RX);
            _prevClutch = clutchAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Buttons (only on first call or when changed)
        var btns = state.ButtonStates;
        if (!_buttonsInitialized)
        {
            // First call: set all buttons
            for (int i = 0; i < 24; i++)
            {
                SetBtn(btns[i], VJD_ID, (byte)(i + 1));
                _prevButtons[i] = btns[i];
            }
            _buttonsInitialized = true;
            _totalCalls += 24;
        }
        else
        {
            // Subsequent: only update changed buttons
            for (int i = 0; i < 24; i++)
            {
                if (btns[i] != _prevButtons[i])
                {
                    SetBtn(btns[i], VJD_ID, (byte)(i + 1));
                    _prevButtons[i] = btns[i];
                    _totalCalls++;
                }
                else { _skippedCalls++; }
            }
        }
    }

    public void Dispose()
    {
        StopOutputLoop();
        if (_acquired)
        {
            OnLog?.Invoke($"vJoy stats: {_totalCalls} P/Invoke calls, {_skippedCalls} skipped ({100.0 * _skippedCalls / Math.Max(1, _totalCalls + _skippedCalls):F1}% saved)");
            ResetVJD(VJD_ID);
            RelinquishVJD(VJD_ID);
            _acquired = false;
        }
    }
}