using System.Runtime.InteropServices;

namespace OmniWheelPC.Network;

/// <summary>
/// Optimized vJoy controller with dirty tracking.
/// Only calls P/Invoke SetAxis/SetBtn when values actually change.
/// Reduces P/Invoke calls from ~132/1ms to typically 1-5/1ms.
/// </summary>
public class VJoyController : IDisposable
{
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

    // Dirty tracking: only update when values change
    private long _prevSteering = long.MinValue;
    private long _prevThrottle = long.MinValue;
    private long _prevBrake = long.MinValue;
    private long _prevClutch = long.MinValue;
    private bool[] _prevButtons = new bool[16];
    private bool _buttonsInitialized = false;

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
                OnLog?.Invoke("vJoy is not enabled");
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
                OnLog?.Invoke("Failed to acquire vJoy device (is another app using it?)");
                return false;
            }
            ResetVJD(VJD_ID);
            _acquired = true;
            OnLog?.Invoke("vJoy device 1 acquired (dirty-tracking optimized)");
            return true;
        }
        catch (DllNotFoundException)
        {
            OnLog?.Invoke("vJoyInterface.dll not found — install vJoy");
            return false;
        }
        catch (Exception ex)
        {
            OnLog?.Invoke($"vJoy init error: {ex.Message}");
            return false;
        }
    }

    /// <summary>
    /// Update vJoy with current input state. Only calls P/Invoke when values changed.
    /// </summary>
    public void Update(InputState state)
    {
        if (!_acquired) return;

        // Steering: -32768..32767 -> 0..32767
        long steeringAxis = AXIS_CENTER + (state.Steering / 2);
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
        long throttleAxis = (long)((state.Throttle / 255.0) * AXIS_MAX);
        if (throttleAxis != _prevThrottle)
        {
            SetAxis(throttleAxis, VJD_ID, HID_USAGES.HID_USAGE_Y);
            _prevThrottle = throttleAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Brake
        long brakeAxis = (long)((state.Brake / 255.0) * AXIS_MAX);
        if (brakeAxis != _prevBrake)
        {
            SetAxis(brakeAxis, VJD_ID, HID_USAGES.HID_USAGE_Z);
            _prevBrake = brakeAxis;
            _totalCalls++;
        }
        else { _skippedCalls++; }

        // Clutch (inverted)
        long clutchAxis = AXIS_MAX - (long)((state.Clutch / 255.0) * AXIS_MAX);
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
            // First call: set all 16 buttons
            for (int i = 0; i < 16; i++)
            {
                SetBtn(btns[i], VJD_ID, (byte)(i + 1));
                _prevButtons[i] = btns[i];
            }
            _buttonsInitialized = true;
            _totalCalls += 16;
        }
        else
        {
            // Subsequent: only update changed buttons
            for (int i = 0; i < 16; i++)
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
        if (_acquired)
        {
            OnLog?.Invoke($"vJoy stats: {_totalCalls} P/Invoke calls, {_skippedCalls} skipped ({100.0 * _skippedCalls / Math.Max(1, _totalCalls + _skippedCalls):F1}% saved)");
            ResetVJD(VJD_ID);
            RelinquishVJD(VJD_ID);
            _acquired = false;
        }
    }
}