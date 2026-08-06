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
    
    // Colors (match HTML mockup exactly)
    private static readonly Color BgDark = Color.FromArgb(12, 12, 22);
    private static readonly Color BgHeader = Color.FromArgb(22, 22, 40);
    private static readonly Color BgDeviceList = Color.FromArgb(18, 18, 32);
    private static readonly Color BgDeviceItem = Color.FromArgb(28, 28, 52);
    private static readonly Color BgLog = Color.FromArgb(18, 18, 32);
    private static readonly Color BgBar = Color.FromArgb(35, 35, 64);
    private static readonly Color Accent = Color.FromArgb(99, 102, 241);
    private static readonly Color Green = Color.FromArgb(34, 197, 94);
    private static readonly Color Red = Color.FromArgb(239, 68, 68);
    private static readonly Color Yellow = Color.FromArgb(245, 158, 11);
    private static readonly Color TextMain = Color.FromArgb(220, 220, 230);
    private static readonly Color TextDim = Color.FromArgb(130, 130, 155);
    private static readonly Color TextMuted = Color.FromArgb(70, 70, 80);
    private static readonly Color SectionLabel = Color.FromArgb(70, 70, 80);
    
    // Fonts (match HTML mockup exactly)
    private static readonly Font FntTitle = new("Segoe UI", 18f, FontStyle.Bold);
    private static readonly Font FntVer = new("Segoe UI", 8f);
    private static readonly Font FntStatus = new("Segoe UI", 9f);
    private static readonly Font FntSection = new("Segoe UI", 8f, FontStyle.Bold);
    private static readonly Font FntInput = new("Segoe UI", 8f, FontStyle.Bold);
    private static readonly Font FntVal = new("Cascadia Code", 9f);
    private static readonly Font FntDevItem = new("Segoe UI", 9f);
    private static readonly Font FntDevIP = new("Segoe UI", 8f);
    private static readonly Font FntInfo = new("Segoe UI", 9f);
    private static readonly Font FntInfoSm = new("Segoe UI", 8f);
    private static readonly Font FntLog = new("Cascadia Code", 7.5f);
    private static readonly Font FntBtn = new("Segoe UI", 9f, FontStyle.Bold);
    
    // Layout constants
    private const int Pad = 16;
    private const int HeaderH = 48;
    private const int DeviceListH = 72;
    private const int DeviceSectionH = 80; // label + list
    private const int InputLabelH = 22;
    private const int BarRowH = 26;
    private const int InfoRowH = 24;
    private const int LogLabelH = 16;
    private const int MinBarW = 150;
    
    // Controls
    private Panel _header = null!;
    private Label _statusDot = null!;
    private Label _statusLabel = null!;
    private Label _titleLabel = null!;
    private Label _versionLabel = null!;
    private ListBox _deviceList = null!;
    private Button _refreshBtn = null!;
    private Button _readyBtn = null!;
    private TextBox _logBox = null!;
    private Label _secLabel2 = null!;
    private Label _vjoyLabel = null!;
    private Label _secLabel3 = null!;
    
    // Input bars: [label, value, barBg, barFg] x4 (steering, gas, brake, clutch)
    private readonly Label[] _barLabels = new Label[4];
    private readonly Label[] _barVals = new Label[4];
    private readonly Panel[] _barBgs = new Panel[4];
    private readonly Panel[] _barFgs = new Panel[4];
    
    private Label _buttonsLabel = null!;
    private Label _packetLabel = null!;
    
    private readonly System.Windows.Forms.Timer _uiTimer;
    private int _packetCount;
    private int _packetsPerSecond;
    private DateTime _lastPpsTime = DateTime.UtcNow;
    private int _lastPpsCount = 0;
    private bool _layoutBuilt;
    private bool _cleanedUp;
    
    public MainForm()
    {
        Text = "OmniWheel PC";
        ClientSize = new Size(540, 520);
        MinimumSize = new Size(440, 420);
        BackColor = BgDark;
        FormBorderStyle = FormBorderStyle.Sizable;
        StartPosition = FormStartPosition.CenterScreen;
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
        
        _uiTimer = new System.Windows.Forms.Timer { Interval = 15 }; // ~67 FPS for ultra-smooth UI
        _uiTimer.Tick += UpdateUI;
        
        BuildUI();
        Resize += (_, _) => Relayout();
        
        Load += (s, e) =>
        {
            _vJoy.Initialize();
            _discovery.Start();
            _input.Start();
            _uiTimer.Start();
            _vJoyTimer.Start();
            Log("OmniWheel PC v0.8.9 started (protocol v2)");
            Log("Ports: Discovery=19700  Input=19701");
            Log("Allow UDP 19701 in Windows Firewall if no input works");
        };
        FormClosing += (s, e) => Cleanup();
    }
    
    private void Cleanup()
    {
        if (_cleanedUp) return;
        _cleanedUp = true;
        try
        {
            _uiTimer.Stop();
            _vJoyTimer.Stop();
            _discovery.Dispose();
        }
        catch (ObjectDisposedException) { }
        try { _input.Dispose(); } catch (ObjectDisposedException) { }
        try { _vJoy.Dispose(); } catch (ObjectDisposedException) { }
    }
    
    private static GraphicsPath RoundedRectPath(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        var rad = Math.Min(radius, r.Width / 2);
        rad = Math.Min(rad, r.Height / 2);
        if (rad < 1) { path.AddRectangle(r); return path; }
        path.AddArc(r.X, r.Y, rad * 2, rad * 2, 180, 90);
        path.AddArc(r.Right - rad * 2, r.Y, rad * 2, rad * 2, 270, 90);
        path.AddArc(r.Right - rad * 2, r.Bottom - rad * 2, rad * 2, rad * 2, 0, 90);
        path.AddArc(r.X, r.Bottom - rad * 2, rad * 2, rad * 2, 90, 90);
        path.CloseFigure();
        return path;
    }
    
    private Panel CreateBarBg(int width)
    {
        var bg = new Panel { Height = 8, Width = width, BackColor = BgBar };
        bg.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = RoundedRectPath(bg.ClientRectangle, 4);
            e.Graphics.FillPath(new SolidBrush(BgBar), path);
        };
        return bg;
    }
    
    private Panel CreateBarFill(Color color, Panel parent)
    {
        var fg = new Panel { Height = 8, Width = 0, BackColor = color, Dock = DockStyle.Left };
        fg.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = RoundedRectPath(fg.ClientRectangle, 4);
            e.Graphics.FillPath(new SolidBrush(color), path);
        };
        parent.Controls.Add(fg);
        return fg;
    }
    
    private void BuildUI()
    {
        int y = 0;
        var w = ClientSize.Width;
        
        // ===== HEADER =====
        _header = new Panel { Bounds = new Rectangle(0, y, w, HeaderH), BackColor = BgHeader };
        _titleLabel = new Label { Text = "OmniWheel", Font = FntTitle, ForeColor = Accent, Location = new Point(Pad, 12), AutoSize = true };
        _versionLabel = new Label { Text = "v0.8.9", Font = FntVer, ForeColor = TextMuted, Location = new Point(145, 22), AutoSize = true };
        _statusDot = new Label { Text = "●", Font = new Font("Segoe UI", 12f), ForeColor = TextMuted, Location = new Point(w - 170, 16), AutoSize = true };
        _statusLabel = new Label { Text = "Waiting for phone...", Font = FntStatus, ForeColor = TextDim, Location = new Point(w - 150, 18), AutoSize = true };
        _header.Controls.AddRange(new Control[] { _titleLabel, _versionLabel, _statusDot, _statusLabel });
        Controls.Add(_header);
        y += HeaderH;
        
        // ===== DISCOVERED PHONES =====
        var secLabel1 = new Label { Text = "DISCOVERED PHONES", Font = FntSection, ForeColor = SectionLabel, Location = new Point(Pad, y + 6), AutoSize = true };
        Controls.Add(secLabel1);
        y += 22;
        
        _deviceList = new ListBox
        {
            Bounds = new Rectangle(Pad, y, w - Pad * 2 - 95, DeviceListH),
            BackColor = BgDeviceList, ForeColor = TextMain,
            BorderStyle = BorderStyle.None, Font = FntDevItem,
            SelectionMode = SelectionMode.One,
            ItemHeight = 24
        };
        _deviceList.DrawMode = DrawMode.OwnerDrawFixed;
        _deviceList.DrawItem += (s, e) =>
        {
            e.DrawBackground();
            if (e.Index < 0) return;
            var text = _deviceList.Items[e.Index]?.ToString() ?? "";
            using var bgBrush = new SolidBrush(BgDeviceItem);
            e.Graphics.FillRectangle(bgBrush, e.Bounds.X + 2, e.Bounds.Y + 1, e.Bounds.Width - 4, e.Bounds.Height - 3);
            var parts = text.Split('(');
            e.Graphics.DrawString(parts[0].Trim(), FntDevItem, Brushes.White, e.Bounds.X + 10, e.Bounds.Y + 4);
            if (parts.Length > 1)
                e.Graphics.DrawString("(" + parts[1], FntDevIP, new SolidBrush(Accent), e.Bounds.X + 10, e.Bounds.Y + 18);
            e.DrawFocusRectangle();
        };
        Controls.Add(_deviceList);
        
        var btnX = w - Pad - 85;
        _refreshBtn = new Button
        {
            Text = "Refresh", Font = FntBtn, Size = new Size(85, 28),
            Location = new Point(btnX, y + 4), BackColor = Color.FromArgb(28, 28, 50), ForeColor = TextDim,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            FlatAppearance = { BorderSize = 1, BorderColor = Color.FromArgb(42, 42, 68) }
        };
        _refreshBtn.Click += (s, e) =>
        {
            _deviceList.Items.Clear();
            _discovery.GetDiscoveredDevices().ForEach(d => 
                _deviceList.Items.Add($"{d.DeviceName} ({d.IpAddress})"));
        };
        Controls.Add(_refreshBtn);
        
        _readyBtn = new Button
        {
            Text = "Ready", Font = FntBtn, Size = new Size(85, 28),
            Location = new Point(btnX, y + 38), BackColor = Color.FromArgb(99, 102, 241, 50), ForeColor = Accent,
            FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand,
            FlatAppearance = { BorderSize = 1, BorderColor = Accent }
        };
        _readyBtn.Click += (s, e) =>
        {
            Log($"Ready for input on port {Protocol.InputPort}");
            _statusLabel.Text = "Waiting for input packets...";
            _statusDot.ForeColor = Yellow;
        };
        Controls.Add(_readyBtn);
        y += DeviceListH + 8;
        
        // ===== LIVE INPUT =====
        _secLabel2 = new Label { Text = "LIVE INPUT", Font = FntSection, ForeColor = SectionLabel, Location = new Point(Pad, y + 2), AutoSize = true };
        _vjoyLabel = new Label { Text = "vJoy: --", Font = FntInfoSm, ForeColor = TextMuted, Location = new Point(w - Pad - 100, y + 2), AutoSize = true };
        Controls.Add(_secLabel2);
        Controls.Add(_vjoyLabel);
        y += InputLabelH;
        
        // Bar data: label text, color
        var barData = new (string name, Color color, string unit)[4]
        {
            ("STEERING", TextDim, ""),
            ("GAS", Green, "%"),
            ("BRAKE", Red, "%"),
            ("CLUTCH", Yellow, "%")
        };
        var barColors = new[] { Accent, Green, Red, Yellow };
        
        int barW = Math.Max(w - Pad * 2 - 120, MinBarW);
        
        for (int i = 0; i < 4; i++)
        {
            var (name, color, _) = barData[i];
            _barLabels[i] = new Label { Text = name, Font = FntInput, ForeColor = color, Location = new Point(Pad, y + 4), AutoSize = true };
            _barVals[i] = new Label { Text = i == 0 ? "0.000" : "0%", Font = FntVal, ForeColor = TextMain, Location = new Point(Pad + 68, y + 3), AutoSize = true };
            _barBgs[i] = CreateBarBg(barW);
            _barBgs[i].Location = new Point(Pad + 120, y + 7);
            _barBgs[i].Tag = i; // store index for resize
            _barFgs[i] = CreateBarFill(barColors[i], _barBgs[i]);
            _barFgs[i].Tag = i;
            Controls.AddRange(new Control[] { _barLabels[i], _barVals[i], _barBgs[i] });
            y += BarRowH;
        }
        y += 4;
        
        // Info row
        _buttonsLabel = new Label { Text = "Buttons: --", Font = FntInfo, ForeColor = TextDim, Location = new Point(Pad, y), AutoSize = true };
        _packetLabel = new Label { Text = "--/s", Font = FntInfoSm, ForeColor = Green, Location = new Point(Pad + 264, y), AutoSize = true };
        Controls.AddRange(new Control[] { _buttonsLabel, _packetLabel });
        y += InfoRowH;
        
        // ===== LOG =====
        _secLabel3 = new Label { Text = "LOG", Font = FntSection, ForeColor = SectionLabel, Location = new Point(Pad, y), AutoSize = true };
        Controls.Add(_secLabel3);
        y += LogLabelH;
        
        _logBox = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Location = new Point(Pad, y), Width = w - Pad * 2, Height = 100,
            BackColor = BgLog, ForeColor = TextDim,
            BorderStyle = BorderStyle.None, Font = FntLog
        };
        Controls.Add(_logBox);
        
        _layoutBuilt = true;
    }
    
    private void Relayout()
    {
        if (!_layoutBuilt) return;
        var w = ClientSize.Width;
        var h = ClientSize.Height;
        SuspendLayout();
        
        try
        {
            // Header stretches full width
            _header.Width = w;
            _statusDot.Location = new Point(w - 170, 16);
            _statusLabel.Location = new Point(w - 150, 18);
            
            // Device list stretches, buttons stay on right
            int y = HeaderH + 22;
            _deviceList.Width = w - Pad * 2 - 95;
            int btnX = w - Pad - 85;
            _refreshBtn.Location = new Point(btnX, y + 4);
            _readyBtn.Location = new Point(btnX, y + 38);
            
            // LIVE INPUT label
            y += DeviceListH + 8;
            _vjoyLabel.Location = new Point(w - Pad - 100, y + 2);
            y += InputLabelH;
            
            // Bars stretch horizontally
            int barW = Math.Max(w - Pad * 2 - 120, MinBarW);
            for (int i = 0; i < 4; i++)
            {
                _barBgs[i].Width = barW;
                // Clamp fill width to new bar width
                if (_barFgs[i].Width > barW) _barFgs[i].Width = barW;
                y += BarRowH;
            }
            
            // Log box fills remaining space
            y += 4 + InfoRowH + LogLabelH;
            int logH = Math.Max(h - y - Pad, 40);
            _logBox.Width = w - Pad * 2;
            _logBox.Height = logH;
        }
        finally
        {
            ResumeLayout(false);
        }
    }
    
    private void OnDeviceFound(DiscoveredDevice device)
    {
        if (InvokeRequired) { BeginInvoke(() => OnDeviceFound(device)); return; }
        var text = $"{device.DeviceName} ({device.IpAddress})";
        if (!_deviceList.Items.Cast<string>().Any(s => s.Contains(device.IpAddress)))
            _deviceList.Items.Add(text);
    }
    
    private void OnInputReceived() 
    { 
        _packetCount++;
        if (_vJoy.IsAvailable)
        {
            _vJoy.Update(_input.CurrentState);
        }
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
        var barW = _barBgs[0].Width;
        
        // Steering
        float str = s.NormalizedSteering;
        _barVals[0].Text = $"{str:F3}";
        _barVals[0].ForeColor = Math.Abs(str) > 0.01f ? Accent : TextMain;
        _barFgs[0].Width = Math.Min((int)(Math.Abs(str) * barW), barW);
        _barFgs[0].BackColor = Accent;
        
        // Gas
        float gas = s.NormalizedThrottle;
        _barVals[1].Text = $"{(int)(gas * 100)}%";
        _barVals[1].ForeColor = gas > 0.01f ? Green : TextMain;
        _barFgs[1].Width = (int)(gas * barW);
        _barFgs[1].BackColor = Green;
        
        // Brake
        float brk = s.NormalizedBrake;
        _barVals[2].Text = $"{(int)(brk * 100)}%";
        _barVals[2].ForeColor = brk > 0.01f ? Red : TextMain;
        _barFgs[2].Width = (int)(brk * barW);
        _barFgs[2].BackColor = Red;
        
        // Clutch
        float clu = s.NormalizedClutch;
        _barVals[3].Text = $"{(int)(clu * 100)}%";
        _barVals[3].ForeColor = clu > 0.01f ? Yellow : TextMain;
        _barFgs[3].Width = (int)(clu * barW);
        _barFgs[3].BackColor = Yellow;
        
        // Buttons — vJoy numbers
        var pressed = new List<string>();
        for (int i = 0; i < s.ButtonStates.Length; i++)
            if (s.ButtonStates[i]) pressed.Add($"{(i + 1)}");
        _buttonsLabel.Text = pressed.Count > 0
            ? $"Buttons: {string.Join(", ", pressed)}"
            : "Buttons: --";
        _buttonsLabel.ForeColor = pressed.Count > 0 ? Accent : TextDim;
        
        // Packets
        var now = DateTime.UtcNow;
        var elapsedSec = (now - _lastPpsTime).TotalSeconds;
        if (elapsedSec >= 0.5)
        {
            _packetsPerSecond = (int)((_packetCount - _lastPpsCount) / elapsedSec);
            _lastPpsCount = _packetCount;
            _lastPpsTime = now;
        }
        if (_input.IsConnected)
        {
            _packetLabel.Text = $"{_packetsPerSecond}/s";
            _packetLabel.ForeColor = _packetsPerSecond > 50 ? Green : Yellow;
        }
        else { _packetLabel.Text = "--/s"; _packetLabel.ForeColor = TextMuted; }
        
        // vJoy — show real error reason
        if (_vJoy.IsAvailable)
        {
            _vjoyLabel.Text = "vJoy: Active (Device 1)";
            _vjoyLabel.ForeColor = Green;
        }
        else if (!string.IsNullOrEmpty(_vJoy.InitError))
        {
            _vjoyLabel.Text = $"vJoy: {_vJoy.InitError}";
            _vjoyLabel.ForeColor = Red;
        }
        else
        {
            _vjoyLabel.Text = "vJoy: Initializing...";
            _vjoyLabel.ForeColor = Yellow;
        }
    }
    
    private void Log(string msg)
    {
        if (InvokeRequired) { BeginInvoke(() => Log(msg)); return; }
        _logBox.AppendText($"[{DateTime.Now:HH:mm:ss.fff}] {msg}\r\n");
    }
    
    protected override void Dispose(bool disposing)
    {
        Cleanup();
        base.Dispose(disposing);
    }
}