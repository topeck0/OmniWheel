using System;
using System.Drawing;
using System.Drawing.Drawing2D;
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
    
    // Colors
    private static readonly Color BgDark = Color.FromArgb(12, 12, 22);
    private static readonly Color BgSurface = Color.FromArgb(22, 22, 40);
    private static readonly Color BgSurface2 = Color.FromArgb(18, 18, 32);
    private static readonly Color BgInput = Color.FromArgb(28, 28, 50);
    private static readonly Color Accent = Color.FromArgb(99, 102, 241);
    private static readonly Color AccentDim = Color.FromArgb(99, 102, 241, 60);
    private static readonly Color Green = Color.FromArgb(34, 197, 94);
    private static readonly Color Red = Color.FromArgb(239, 68, 68);
    private static readonly Color Yellow = Color.FromArgb(245, 158, 11);
    private static readonly Color Cyan = Color.FromArgb(34, 211, 238);
    private static readonly Color TextMain = Color.FromArgb(220, 220, 230);
    private static readonly Color TextDim = Color.FromArgb(130, 130, 155);
    private static readonly Color TextMuted = Color.FromArgb(70, 70, 95);
    
    // Fonts
    private static readonly Font Fnt8 = new("Segoe UI", 8f);
    private static readonly Font Fnt9 = new("Segoe UI", 9f);
    private static readonly Font Fnt9B = new("Segoe UI", 9f, FontStyle.Bold);
    private static readonly Font Fnt10 = new("Segoe UI", 10f);
    private static readonly Font Fnt10B = new("Segoe UI", 10f, FontStyle.Bold);
    private static readonly Font Fnt14B = new("Segoe UI", 14f, FontStyle.Bold);
    private static readonly Font Fnt18B = new("Segoe UI", 18f, FontStyle.Bold);
    private static readonly Font FntMono = new("Cascadia Code", 8f);
    
    // Controls
    private Panel _headerPanel = null!;
    private Label _statusDot = null!;
    private Label _statusLabel = null!;
    private Label _titleLabel = null!;
    private Label _versionLabel = null!;
    private ListBox _deviceList = null!;
    private Button _refreshBtn = null!;
    private TextBox _logBox = null!;
    
    // Input display controls
    private Panel _steeringBarBg = null!;
    private Panel _steeringBarFg = null!;
    private Label _steeringVal = null!;
    private Panel _throttleBarBg = null!;
    private Panel _throttleBarFg = null!;
    private Label _throttleVal = null!;
    private Panel _brakeBarBg = null!;
    private Panel _brakeBarFg = null!;
    private Label _brakeVal = null!;
    private Panel _clutchBarBg = null!;
    private Panel _clutchBarFg = null!;
    private Label _clutchVal = null!;
    private Label _buttonsLabel = null!;
    private Label _packetLabel = null!;
    private Label _vjoyLabel = null!;
    private Label _gyroLabel = null!;
    
    private readonly System.Windows.Forms.Timer _uiTimer;
    private DateTime _lastInputTime;
    private int _packetCount;
    private int _lastPacketCount;
    private int _packetsPerSecond;
    
    public MainForm()
    {
        Text = "OmniWheel PC";
        Size = new Size(540, 720);
        MinimumSize = new Size(440, 600);
        BackColor = BgDark;
        FormBorderStyle = FormBorderStyle.Sizable;
        StartPosition = FormStartPosition.CenterScreen;
        Icon = SystemIcons.Application;
        DoubleBuffered = true;
        
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
        
        _uiTimer = new System.Windows.Forms.Timer { Interval = 100 };
        _uiTimer.Tick += UpdateUI;
        
        BuildUI();
        
        Load += (s, e) =>
        {
            _vJoy.Initialize();
            _discovery.Start();
            _input.Start();
            _uiTimer.Start();
            _vJoyTimer.Start();
            Log("OmniWheel PC v0.8.5 started (protocol v2)");
            Log("Ports: Discovery=19700  Input=19701");
            Log("Allow UDP 19701 in Windows Firewall if no input works");
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
    
    private Panel MakeBar(Color trackColor)
    {
        var bg = new Panel
        {
            Height = 8,
            BackColor = trackColor,
        };
        // Rounded corners via region
        bg.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = CreateRoundedRect(bg.ClientRectangle, 4);
            e.Graphics.FillPath(new SolidBrush(trackColor), path);
        };
        return bg;
    }
    
    private Panel MakeBarFill(Color fillColor, Panel parent)
    {
        var fg = new Panel
        {
            Height = 8,
            Width = 0,
            BackColor = fillColor,
            Dock = DockStyle.Left,
        };
        fg.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = CreateRoundedRect(fg.ClientRectangle, 4);
            e.Graphics.FillPath(new SolidBrush(fillColor), path);
        };
        parent.Controls.Add(fg);
        return fg;
    }
    
    private static GraphicsPath CreateRoundedRect(Rectangle rect, float radius)
    {
        var path = new GraphicsPath();
        var r = Math.Min(radius, rect.Width / 2f);
        r = Math.Min(r, rect.Height / 2f);
        if (r < 1) { path.AddRectangle(rect); return path; }
        path.AddArc(rect.X, rect.Y, r * 2, r * 2, 180, 90);
        path.AddArc(rect.Right - r * 2, rect.Y, r * 2, r * 2, 270, 90);
        path.AddArc(rect.Right - r * 2, rect.Bottom - r * 2, r * 2, r * 2, 0, 90);
        path.AddArc(rect.X, rect.Bottom - r * 2, r * 2, r * 2, 90, 90);
        path.CloseFigure();
        return path;
    }
    
    private void BuildUI()
    {
        // ===== HEADER =====
        _headerPanel = new Panel { Dock = DockStyle.Top, Height = 56, BackColor = BgSurface, Padding = new Padding(16, 0, 16, 0) };
        _titleLabel = new Label
        {
            Text = "OmniWheel", Font = Fnt18B, ForeColor = Accent,
            Location = new Point(16, 10), AutoSize = true
        };
        _versionLabel = new Label
        {
            Text = "v0.8.5", Font = Fnt8, ForeColor = TextMuted,
            Location = new Point(155, 20), AutoSize = true
        };
        _statusDot = new Label
        {
            Text = "●", Font = new Font("Segoe UI", 12f), ForeColor = TextMuted,
            Location = new Point(16, 34), AutoSize = true
        };
        _statusLabel = new Label
        {
            Text = "Waiting for phone...", Font = Fnt9, ForeColor = TextDim,
            Location = new Point(34, 35), AutoSize = true
        };
        _headerPanel.Controls.AddRange(new Control[] { _titleLabel, _versionLabel, _statusDot, _statusLabel });
        
        // ===== DEVICE PANEL =====
        var devicePanel = new Panel { Dock = DockStyle.Top, Height = 160, BackColor = BgDark, Padding = new Padding(16, 8, 16, 8) };
        var deviceTitle = new Label
        {
            Text = "DISCOVERED PHONES", Font = Fnt8, ForeColor = TextMuted,
            Location = new Point(16, 8), AutoSize = true
        };
        _deviceList = new ListBox
        {
            Location = new Point(16, 28), Size = new Size(390, 85),
            BackColor = BgSurface2, ForeColor = TextMain,
            BorderStyle = BorderStyle.None, Font = Fnt9,
            SelectionMode = SelectionMode.One
        };
        _refreshBtn = new Button
        {
            Text = "Refresh", Font = Fnt9, Size = new Size(75, 28),
            Location = new Point(420, 28), BackColor = BgSurface, ForeColor = TextDim,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            FlatAppearance = { BorderSize = 1, BorderColor = Color.FromArgb(40, 40, 65) }
        };
        _refreshBtn.Click += (s, e) =>
        {
            _deviceList.Items.Clear();
            _discovery.GetDiscoveredDevices().ForEach(d => 
                _deviceList.Items.Add($"{d.DeviceName}  ({d.IpAddress})"));
        };
        var connectBtn = new Button
        {
            Text = "Ready", Font = Fnt9B, Size = new Size(75, 28),
            Location = new Point(420, 64), BackColor = AccentDim, ForeColor = Accent,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            FlatAppearance = { BorderSize = 1, BorderColor = Accent }
        };
        connectBtn.Click += (s, e) =>
        {
            if (_deviceList.SelectedIndex < 0)
            {
                _statusLabel.Text = "Select a device or wait for auto-connect";
                _statusDot.ForeColor = Yellow;
                return;
            }
            Log($"Ready for input on port {Protocol.InputPort}");
            _statusLabel.Text = "Waiting for input packets...";
            _statusDot.ForeColor = Yellow;
        };
        var fwLabel = new Label
        {
            Text = "No input? Allow UDP 19701 in Windows Firewall",
            Font = new Font("Segoe UI", 7.5f), ForeColor = Color.FromArgb(80, 60, 60),
            Location = new Point(16, 120), AutoSize = true
        };
        devicePanel.Controls.AddRange(new Control[] { deviceTitle, _deviceList, _refreshBtn, connectBtn, fwLabel });

        // ===== INPUT PANEL =====
        var inputPanel = new Panel { Dock = DockStyle.Top, Height = 230, BackColor = BgDark, Padding = new Padding(16, 8, 16, 8) };
        var inputTitle = new Label
        {
            Text = "LIVE INPUT", Font = Fnt8, ForeColor = TextMuted,
            Location = new Point(16, 4), AutoSize = true
        };
        _vjoyLabel = new Label
        {
            Text = "vJoy: --", Font = Fnt8, ForeColor = TextMuted,
            Location = new Point(400, 4), AutoSize = true
        };
        
        int y = 24;
        int labelX = 16;
        int valX = 75;
        int barX = 140;
        int barW = 300;
        int rowH = 34;

        // Steering row
        var strLabel = new Label { Text = "STEERING", Font = Fnt8, ForeColor = TextDim, Location = new Point(labelX, y + 2), AutoSize = true };
        _steeringVal = new Label { Text = "0.000", Font = FntMono, ForeColor = TextMain, Location = new Point(valX, y), AutoSize = true };
        _steeringBarBg = MakeBar(Color.FromArgb(35, 35, 60)); _steeringBarBg.Location = new Point(barX, y + 4); _steeringBarBg.Size = new Size(barW, 8);
        _steeringBarFg = MakeBarFill(Accent, _steeringBarBg);
        inputPanel.Controls.AddRange(new Control[] { strLabel, _steeringVal, _steeringBarBg });
        y += rowH;

        // Throttle row
        var thrLabel = new Label { Text = "GAS", Font = Fnt8, ForeColor = Green, Location = new Point(labelX, y + 2), AutoSize = true };
        _throttleVal = new Label { Text = "0%", Font = FntMono, ForeColor = TextMain, Location = new Point(valX, y), AutoSize = true };
        _throttleBarBg = MakeBar(Color.FromArgb(35, 35, 60)); _throttleBarBg.Location = new Point(barX, y + 4); _throttleBarBg.Size = new Size(barW, 8);
        _throttleBarFg = MakeBarFill(Green, _throttleBarBg);
        inputPanel.Controls.AddRange(new Control[] { thrLabel, _throttleVal, _throttleBarBg });
        y += rowH;

        // Brake row
        var brkLabel = new Label { Text = "BRAKE", Font = Fnt8, ForeColor = Red, Location = new Point(labelX, y + 2), AutoSize = true };
        _brakeVal = new Label { Text = "0%", Font = FntMono, ForeColor = TextMain, Location = new Point(valX, y), AutoSize = true };
        _brakeBarBg = MakeBar(Color.FromArgb(35, 35, 60)); _brakeBarBg.Location = new Point(barX, y + 4); _brakeBarBg.Size = new Size(barW, 8);
        _brakeBarFg = MakeBarFill(Red, _brakeBarBg);
        inputPanel.Controls.AddRange(new Control[] { brkLabel, _brakeVal, _brakeBarBg });
        y += rowH;

        // Clutch row
        var cluLabel = new Label { Text = "CLUTCH", Font = Fnt8, ForeColor = Yellow, Location = new Point(labelX, y + 2), AutoSize = true };
        _clutchVal = new Label { Text = "0%", Font = FntMono, ForeColor = TextMain, Location = new Point(valX, y), AutoSize = true };
        _clutchBarBg = MakeBar(Color.FromArgb(35, 35, 60)); _clutchBarBg.Location = new Point(barX, y + 4); _clutchBarBg.Size = new Size(barW, 8);
        _clutchBarFg = MakeBarFill(Yellow, _clutchBarBg);
        inputPanel.Controls.AddRange(new Control[] { cluLabel, _clutchVal, _clutchBarBg });
        y += rowH;

        // Buttons & packet row
        _buttonsLabel = new Label { Text = "Buttons: --", Font = Fnt9, ForeColor = TextDim, Location = new Point(labelX, y + 2), AutoSize = true };
        _packetLabel = new Label { Text = "Packets: --/s", Font = Fnt8, ForeColor = TextMuted, Location = new Point(280, y + 2), AutoSize = true };
        _gyroLabel = new Label { Text = "", Font = Fnt8, ForeColor = TextMuted, Location = new Point(400, y + 2), AutoSize = true };
        inputPanel.Controls.AddRange(new Control[] { _buttonsLabel, _packetLabel, _gyroLabel });
        
        inputPanel.Controls.AddRange(new Control[] { inputTitle, _vjoyLabel });
        
        // ===== LOG PANEL =====
        var logPanel = new Panel { Dock = DockStyle.Fill, BackColor = BgDark, Padding = new Padding(16, 4, 16, 8) };
        var logTitle = new Label
        {
            Text = "LOG", Font = Fnt8, ForeColor = TextMuted,
            Location = new Point(16, 2), AutoSize = true
        };
        _logBox = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Location = new Point(16, 20), Dock = DockStyle.Bottom, Height = 130,
            BackColor = BgSurface2, ForeColor = TextDim,
            BorderStyle = BorderStyle.None, Font = FntMono
        };
        logPanel.Controls.AddRange(new Control[] { logTitle, _logBox });
        
        Controls.Add(logPanel);
        Controls.Add(inputPanel);
        Controls.Add(devicePanel);
        Controls.Add(_headerPanel);
    }
    
    private void OnDeviceFound(DiscoveredDevice device)
    {
        if (InvokeRequired) { BeginInvoke(() => OnDeviceFound(device)); return; }
        var text = $"{device.DeviceName}  ({device.IpAddress})";
        if (!_deviceList.Items.Cast<string>().Any(s => s.Contains(device.IpAddress)))
            _deviceList.Items.Add(text);
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
        _statusDot.ForeColor = Green;
    }
    
    private void OnDeviceDisconnected()
    {
        if (InvokeRequired) { BeginInvoke(OnDeviceDisconnected); return; }
        _statusLabel.Text = "Waiting for phone...";
        _statusDot.ForeColor = TextMuted;
    }
    
    private void UpdateUI(object? sender, EventArgs e)
    {
        var s = _input.CurrentState;
        
        // Steering
        float strNorm = s.NormalizedSteering;
        _steeringVal.Text = $"{strNorm:F3}";
        _steeringVal.ForeColor = Math.Abs(strNorm) > 0.01f ? Accent : TextMain;
        int strBarW = (int)(Math.Abs(strNorm) * 150);
        _steeringBarFg.Width = Math.Min(strBarW, 300);
        _steeringBarFg.BackColor = Accent;

        // Throttle
        float thrPct = s.NormalizedThrottle;
        _throttleVal.Text = $"{(int)(thrPct * 100)}%";
        _throttleVal.ForeColor = thrPct > 0.01f ? Green : TextMain;
        _throttleBarFg.Width = (int)(thrPct * 300);
        _throttleBarFg.BackColor = Green;
        
        // Brake
        float brkPct = s.NormalizedBrake;
        _brakeVal.Text = $"{(int)(brkPct * 100)}%";
        _brakeVal.ForeColor = brkPct > 0.01f ? Red : TextMain;
        _brakeBarFg.Width = (int)(brkPct * 300);
        _brakeBarFg.BackColor = Red;
        
        // Clutch
        float cluPct = s.NormalizedClutch;
        _clutchVal.Text = $"{(int)(cluPct * 100)}%";
        _clutchVal.ForeColor = cluPct > 0.01f ? Yellow : TextMain;
        _clutchBarFg.Width = (int)(cluPct * 300);
        _clutchBarFg.BackColor = Yellow;
        
        // Buttons — show as vJoy button numbers
        var btns = s.ButtonStates;
        var pressed = new List<string>();
        for (int i = 0; i < 16; i++)
        {
            if (btns[i]) pressed.Add($"{(i + 1)}");
        }
        _buttonsLabel.Text = pressed.Count > 0 
            ? $"Buttons: {string.Join(", ", pressed)}" 
            : "Buttons: --";
        _buttonsLabel.ForeColor = pressed.Count > 0 ? Accent : TextDim;
        
        // Gyro
        if (s.GyroX != 0 || s.GyroY != 0 || s.GyroZ != 0)
            _gyroLabel.Text = $"Gyro: {s.GyroX},{s.GyroY},{s.GyroZ}";
        
        // Packets
        _packetsPerSecond = _packetCount - _lastPacketCount;
        _lastPacketCount = _packetCount;
        if (_input.IsConnected)
        {
            _packetLabel.Text = $"{_packetsPerSecond * 10}/s";
            _packetLabel.ForeColor = _packetsPerSecond * 10 > 50 ? Green : Yellow;
        }
        else
        {
            _packetLabel.Text = "--/s";
            _packetLabel.ForeColor = TextMuted;
        }
        
        // vJoy status
        _vjoyLabel.Text = _vJoy.IsAvailable
            ? "vJoy: Active (Device 1)" 
            : "vJoy: Not installed";
        _vjoyLabel.ForeColor = _vJoy.IsAvailable ? Green : Red;
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