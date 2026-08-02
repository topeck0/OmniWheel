using System;
using System.Drawing;
using System.Linq;
using System.Windows.Forms;
using OmniWheelPC.Network;

namespace OmniWheelPC.UI;

public class MainForm : Form
{
    private readonly DiscoveryServer _discovery;
    private readonly InputReceiver _input;
    private readonly VJoyController _vJoy;
    private readonly System.Windows.Forms.Timer _vJoyTimer;
    
    private Panel _headerPanel = null!;
    private Label _titleLabel = null!;
    private Label _statusLabel = null!;
    private Label _statusDot = null!;
    private ListBox _deviceList = null!;
    private Button _refreshBtn = null!;
    private Button _connectBtn = null!;
    private GroupBox _inputGroup = null!;
    private Label _steeringLabel = null!;
    private Label _throttleLabel = null!;
    private Label _brakeLabel = null!;
    private Label _clutchLabel = null!;
    private Label _latencyLabel = null!;
    private Label _vjoyLabel = null!;
    private Label _buttonsLabel = null!;
    private Label _gyroLabel = null!;
    private Label _packetRateLabel = null!;
    private Label _firewallLabel = null!;
    private TextBox _logBox = null!;
    private readonly System.Windows.Forms.Timer _uiTimer;
    
    private DateTime _lastInputTime;
    private int _packetCount;
    private int _lastPacketCount;
    private int _packetsPerSecond;
    
    public MainForm()
    {
        Text = "OmniWheel PC v0.6.1";
        Size = new Size(580, 780);
        MinimumSize = new Size(440, 550);
        BackColor = Color.FromArgb(15, 15, 26);
        FormBorderStyle = FormBorderStyle.Sizable;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = SystemIcons.Application;
        
        _discovery = new DiscoveryServer { DeviceName = Environment.MachineName };
        _discovery.OnDeviceFound += OnDeviceFound;
        _discovery.OnLog += Log;
        
        _input = new InputReceiver();
        _input.OnInputReceived += OnInputReceived;
        _input.OnConnected += OnDeviceConnected;
        _input.OnDisconnected += OnDeviceDisconnected;
        _input.OnLog += Log;

        _vJoy = new VJoyController();
        _vJoy.OnLog += Log;

        _vJoyTimer = new System.Windows.Forms.Timer { Interval = 1 };
        _vJoyTimer.Tick += (_, _) =>
        {
            if (_vJoy.IsAvailable && _input.IsConnected)
                _vJoy.Update(_input.CurrentState);
        };
        
        _uiTimer = new System.Windows.Forms.Timer { Interval = 200 };
        _uiTimer.Tick += UpdateUI;
        
        BuildUI();
        
        Load += (s, e) =>
        {
            _vJoy.Initialize();
            _discovery.Start();
            _input.Start();
            _uiTimer.Start();
            _vJoyTimer.Start();
            Log("OmniWheel PC v0.6.1 started (optimized protocol v2)");
            Log("Ports: Discovery=19700, Input=19701");
            Log("Allow UDP 19701 in Windows Firewall if no input works!");
        };
        FormClosing += (s, e) => Cleanup();;
    }
    
    private void Cleanup()
    {
        _uiTimer.Stop();
        _vJoyTimer.Stop();
        _discovery.Dispose();
        _input.Dispose();
        _vJoy.Dispose();
    }
    
    private void BuildUI()
    {
        var fnt = new Font("Segoe UI", 9f);
        var fntBold = new Font("Segoe UI", 11f, FontStyle.Bold);
        var fntTitle = new Font("Segoe UI", 16f, FontStyle.Bold);
        var accent = Color.FromArgb(99, 102, 241);
        var textMain = Color.FromArgb(224, 224, 224);
        var surface = Color.FromArgb(26, 26, 46);
        
        _headerPanel = new Panel { Dock = DockStyle.Top, Height = 60, BackColor = surface };
        _titleLabel = new Label
        {
            Text = "OmniWheel", Font = fntTitle, ForeColor = accent,
            Location = new Point(20, 8), AutoSize = true
        };
        _statusDot = new Label
        {
            Text = "●", Font = new Font("Segoe UI", 14f), ForeColor = Color.FromArgb(100, 100, 100),
            Location = new Point(20, 35), AutoSize = true
        };
        _statusLabel = new Label
        {
            Text = "Waiting for connection...", Font = fnt, ForeColor = Color.FromArgb(160, 160, 160),
            Location = new Point(40, 38), AutoSize = true
        };
        _headerPanel.Controls.AddRange(new Control[] { _titleLabel, _statusDot, _statusLabel });
        
        var devicePanel = new Panel { Dock = DockStyle.Top, Height = 250 };
        var deviceTitle = new Label
        {
            Text = "Discovered Phones", Font = new Font("Segoe UI", 10f, FontStyle.Bold), ForeColor = textMain,
            Location = new Point(20, 5), AutoSize = true
        };
        _deviceList = new ListBox
        {
            Location = new Point(20, 30), Size = new Size(520, 150),
            BackColor = surface, ForeColor = textMain, BorderStyle = BorderStyle.FixedSingle,
            Font = fnt
        };
        _refreshBtn = new Button
        {
            Text = "Refresh", Font = fnt, Size = new Size(80, 32),
            Location = new Point(370, 195), BackColor = surface, ForeColor = textMain,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand
        };
        _refreshBtn.Click += (s, e) =>
        {
            _deviceList.Items.Clear();
            _discovery.GetDiscoveredDevices().ForEach(d => 
                _deviceList.Items.Add($"{d.DeviceName} ({d.IpAddress})"));
        };
        _connectBtn = new Button
        {
            Text = "Connect", Font = new Font("Segoe UI", 10f, FontStyle.Bold), Size = new Size(100, 32),
            Location = new Point(460, 195), BackColor = accent, ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand
        };
        _connectBtn.Click += OnConnectClick;
        devicePanel.Controls.AddRange(new Control[] { deviceTitle, _deviceList, _refreshBtn, _connectBtn });
        
        _inputGroup = new GroupBox
        {
            Text = "Live Input", Font = fnt, ForeColor = textMain,
            Dock = DockStyle.Top, Height = 250, Location = new Point(0, 250),
            BackColor = surface
        };
        _steeringLabel = new Label { Text = "Steering: 0.000 (0deg)", Font = fnt, ForeColor = textMain, Location = new Point(20, 25), AutoSize = true };
        _throttleLabel = new Label { Text = "Throttle: 0%", Font = fnt, ForeColor = textMain, Location = new Point(20, 48), AutoSize = true };
        _brakeLabel = new Label { Text = "Brake: 0%", Font = fnt, ForeColor = textMain, Location = new Point(20, 71), AutoSize = true };
        _clutchLabel = new Label { Text = "Clutch: 0%", Font = fnt, ForeColor = textMain, Location = new Point(20, 94), AutoSize = true };
        _buttonsLabel = new Label { Text = "Buttons: --", Font = fnt, ForeColor = Color.FromArgb(140, 140, 140), Location = new Point(20, 117), AutoSize = true };
        _gyroLabel = new Label { Text = "Gyro: X=0 Y=0 Z=0", Font = fnt, ForeColor = Color.FromArgb(140, 140, 140), Location = new Point(20, 140), AutoSize = true };
        _latencyLabel = new Label { Text = "Latency: --", Font = fnt, ForeColor = textMain, Location = new Point(20, 163), AutoSize = true };
        _packetRateLabel = new Label { Text = "Packets: --/s", Font = fnt, ForeColor = Color.FromArgb(140, 140, 140), Location = new Point(20, 186), AutoSize = true };
        _vjoyLabel = new Label { Text = "vJoy: --", Font = fnt, ForeColor = Color.FromArgb(120, 120, 120), Location = new Point(300, 25), AutoSize = true };
        _firewallLabel = new Label { Text = "No input? Allow UDP 19701 in Windows Firewall", Font = new Font("Segoe UI", 8f), ForeColor = Color.FromArgb(100, 70, 70), Location = new Point(300, 48), AutoSize = true };
        _inputGroup.Controls.AddRange(new Control[] { 
            _steeringLabel, _throttleLabel, _brakeLabel, _clutchLabel, 
            _buttonsLabel, _gyroLabel, _latencyLabel, _packetRateLabel, _vjoyLabel, _firewallLabel
        });
        
        var logPanel = new Panel { Dock = DockStyle.Fill, BackColor = Color.FromArgb(10, 10, 20) };
        var logTitle = new Label
        {
            Text = "Log", Font = new Font("Segoe UI", 9f, FontStyle.Bold), ForeColor = Color.FromArgb(120, 120, 120),
            Location = new Point(20, 5), AutoSize = true
        };
        _logBox = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Location = new Point(20, 25), Size = new Size(520, 180),
            BackColor = Color.FromArgb(10, 10, 20), ForeColor = Color.FromArgb(140, 140, 140),
            BorderStyle = BorderStyle.None, Font = new Font("Consolas", 8f)
        };
        logPanel.Controls.AddRange(new Control[] { logTitle, _logBox });
        
        Controls.Add(logPanel);
        Controls.Add(_inputGroup);
        Controls.Add(devicePanel);
        Controls.Add(_headerPanel);
    }
    
    private void OnDeviceFound(DiscoveredDevice device)
    {
        if (InvokeRequired) { BeginInvoke(() => OnDeviceFound(device)); return; }
        var text = $"{device.DeviceName} ({device.IpAddress})";
        if (!_deviceList.Items.Cast<string>().Any(s => s.Contains(device.IpAddress)))
            _deviceList.Items.Add(text);
    }
    
    private void OnConnectClick(object? sender, EventArgs e)
    {
        if (_deviceList.SelectedIndex < 0)
        {
            MessageBox.Show("Select a device first", "OmniWheel", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        Log($"Ready for input from selected device on port {Protocol.InputPort}");
        _statusLabel.Text = "Waiting for input packets...";
        _statusDot.ForeColor = Color.FromArgb(245, 158, 11);
    }
    
    private void OnInputReceived()
    {
        _lastInputTime = DateTime.UtcNow;
        _packetCount++;
    }
    
    private void OnDeviceConnected()
    {
        if (InvokeRequired) { BeginInvoke(OnDeviceConnected); return; }
        _statusLabel.Text = $"Connected: {_input.ConnectedDeviceIp}";
        _statusDot.ForeColor = Color.FromArgb(34, 197, 94);
        _firewallLabel.Text = "Connected — receiving input";
        _firewallLabel.ForeColor = Color.FromArgb(34, 197, 94);
    }
    
    private void OnDeviceDisconnected()
    {
        if (InvokeRequired) { BeginInvoke(OnDeviceDisconnected); return; }
        _statusLabel.Text = "Waiting for connection...";
        _statusDot.ForeColor = Color.FromArgb(100, 100, 100);
        _firewallLabel.Text = "No input? Allow UDP 19701 in Windows Firewall";
        _firewallLabel.ForeColor = Color.FromArgb(100, 70, 70);
    }
    
    private void UpdateUI(object? sender, EventArgs e)
    {
        var s = _input.CurrentState;
        _steeringLabel.Text = $"Steering: {s.NormalizedSteering:F3} ({(int)(s.NormalizedSteering * 900)}deg)";
        _steeringLabel.ForeColor = Math.Abs(s.NormalizedSteering) > 0.01f 
            ? Color.FromArgb(34, 197, 94) : Color.FromArgb(224, 224, 224);
        _throttleLabel.Text = $"Throttle: {(int)(s.NormalizedThrottle * 100)}%";
        _throttleLabel.ForeColor = s.NormalizedThrottle > 0.01f
            ? Color.FromArgb(34, 197, 94) : Color.FromArgb(224, 224, 224);
        _brakeLabel.Text = $"Brake: {(int)(s.NormalizedBrake * 100)}%";
        _brakeLabel.ForeColor = s.NormalizedBrake > 0.01f
            ? Color.FromArgb(239, 68, 68) : Color.FromArgb(224, 224, 224);
        _clutchLabel.Text = $"Clutch: {(int)(s.NormalizedClutch * 100)}%";
        _clutchLabel.ForeColor = s.NormalizedClutch > 0.01f
            ? Color.FromArgb(245, 158, 11) : Color.FromArgb(224, 224, 224);
        
        // Buttons
        var btns = s.ButtonStates;
        var pressed = new List<string>();
        var names = new[] { "A","B","X","Y","LB","RB","UP","DN","LT","RT","L","R","B13","B14","B15","B16" };
        for (int i = 0; i < 16 && i < names.Length; i++)
        {
            if (btns[i]) pressed.Add(names[i]);
        }
        _buttonsLabel.Text = pressed.Count > 0 
            ? $"Buttons: {string.Join(", ", pressed)}" 
            : "Buttons: --";
        _buttonsLabel.ForeColor = pressed.Count > 0 
            ? Color.FromArgb(99, 102, 241) 
            : Color.FromArgb(140, 140, 140);
        
        _gyroLabel.Text = $"Gyro: X={s.GyroX} Y={s.GyroY} Z={s.GyroZ}";
        
        if (_input.IsConnected && s.Timestamp > DateTime.MinValue)
        {
            var latency = (int)(DateTime.UtcNow - s.Timestamp).TotalMilliseconds;
            _latencyLabel.Text = $"Latency: ~{latency}ms (UDP)";
        }
        
        _packetsPerSecond = _packetCount - _lastPacketCount;
        _lastPacketCount = _packetCount;
        _packetRateLabel.Text = _input.IsConnected 
            ? $"Packets: {_packetsPerSecond * 5}/s" 
            : "Packets: --/s";
        _packetRateLabel.ForeColor = _input.IsConnected
            ? (_packetsPerSecond * 5 > 50 ? Color.FromArgb(34, 197, 94) : Color.FromArgb(245, 158, 11))
            : Color.FromArgb(140, 140, 140);
        
        _vjoyLabel.Text = _vJoy.IsAvailable
            ? "vJoy: Active (Device 1)" 
            : "vJoy: Not available (install vJoy)";
        _vjoyLabel.ForeColor = _vJoy.IsAvailable
            ? Color.FromArgb(34, 197, 94)
            : Color.FromArgb(239, 68, 68);
    }
    
    private void Log(string msg)
    {
        if (InvokeRequired) { BeginInvoke(() => Log(msg)); return; }
        _logBox.AppendText($"[{DateTime.Now:HH:mm:ss.fff}] {msg}\r\n");
    }
    
    protected override void Dispose(bool disposing)
    {
        if (disposing) Cleanup();
        base.Dispose(disposing);
    }
}