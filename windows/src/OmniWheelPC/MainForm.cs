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
    
    // Controls
    private Label _statusDot = null!;
    private Label _statusLabel = null!;
    private Label _titleLabel = null!;
    private Label _versionLabel = null!;
    private ListBox _deviceList = null!;
    private Button _refreshBtn = null!;
    private Button _readyBtn = null!;
    private Label _fwLabel = null!;
    private TextBox _logBox = null!;
    
    // Input bars
    private Panel _strBarFg = null!;
    private Label _strVal = null!;
    private Panel _gasBarFg = null!;
    private Label _gasVal = null!;
    private Panel _brkBarFg = null!;
    private Label _brkVal = null!;
    private Panel _cluBarFg = null!;
    private Label _cluVal = null!;
    private Label _buttonsLabel = null!;
    private Label _packetLabel = null!;
    private Label _vjoyLabel = null!;
    
    private readonly System.Windows.Forms.Timer _uiTimer;
    private int _packetCount;
    private int _lastPacketCount;
    private int _packetsPerSecond;
    
    public MainForm()
    {
        Text = "OmniWheel PC";
        Size = new Size(540, 520);
        MinimumSize = new Size(440, 480);
        BackColor = BgDark;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
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
            Log("OmniWheel PC v0.8.7 started (protocol v2)");
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
        var pad = 16;
        int y = 0;
        
        // ===== HEADER (matches HTML) =====
        var header = new Panel { Bounds = new Rectangle(0, y, 540, 48), BackColor = BgHeader };
        _titleLabel = new Label { Text = "OmniWheel", Font = FntTitle, ForeColor = Accent, Location = new Point(pad, 12), AutoSize = true };
        _versionLabel = new Label { Text = "v0.8.7", Font = FntVer, ForeColor = TextMuted, Location = new Point(145, 22), AutoSize = true };
        _statusDot = new Label { Text = "●", Font = new Font("Segoe UI", 12f), ForeColor = TextMuted, Location = new Point(370, 16), AutoSize = true };
        _statusLabel = new Label { Text = "Waiting for phone...", Font = FntStatus, ForeColor = TextDim, Location = new Point(388, 18), AutoSize = true };
        header.Controls.AddRange(new Control[] { _titleLabel, _versionLabel, _statusDot, _statusLabel });
        Controls.Add(header);
        y += 48;
        
        // ===== DISCOVERED PHONES section =====
        var secLabel1 = new Label { Text = "DISCOVERED PHONES", Font = FntSection, ForeColor = SectionLabel, Location = new Point(pad, y + 6), AutoSize = true };
        Controls.Add(secLabel1);
        y += 22;
        
        _deviceList = new ListBox
        {
            Bounds = new Rectangle(pad, y, 410, 72),
            BackColor = BgDeviceList, ForeColor = TextMain,
            BorderStyle = BorderStyle.None, Font = FntDevItem,
            SelectionMode = SelectionMode.One,
            ItemHeight = 24
        };
        // Custom draw device items
        _deviceList.DrawMode = DrawMode.OwnerDrawFixed;
        _deviceList.DrawItem += (s, e) =>
        {
            e.DrawBackground();
            if (e.Index < 0) return;
            var text = _deviceList.Items[e.Index]?.ToString() ?? "";
            // Draw background rect for item
            using var bgBrush = new SolidBrush(BgDeviceItem);
            e.Graphics.FillRectangle(bgBrush, e.Bounds.X + 2, e.Bounds.Y + 1, e.Bounds.Width - 4, e.Bounds.Height - 3);
            // Name
            var parts = text.Split('(');
            e.Graphics.DrawString(parts[0].Trim(), FntDevItem, Brushes.White, e.Bounds.X + 10, e.Bounds.Y + 4);
            // IP
            if (parts.Length > 1)
                e.Graphics.DrawString("(" + parts[1], FntDevIP, new SolidBrush(Accent), e.Bounds.X + 10, e.Bounds.Y + 18);
            e.DrawFocusRectangle();
        };
        Controls.Add(_deviceList);
        
        _refreshBtn = new Button
        {
            Text = "Refresh", Font = FntBtn, Size = new Size(85, 28),
            Location = new Point(438, y + 4), BackColor = Color.FromArgb(28, 28, 50), ForeColor = TextDim,
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
            Location = new Point(438, y + 38), BackColor = Color.FromArgb(99, 102, 241, 50), ForeColor = Accent,
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
        y += 80;
        
        // ===== LIVE INPUT section =====
        var secLabel2 = new Label { Text = "LIVE INPUT", Font = FntSection, ForeColor = SectionLabel, Location = new Point(pad, y + 2), AutoSize = true };
        var vjoyRight = new Label { Text = "vJoy: --", Font = FntInfoSm, ForeColor = TextMuted, Location = new Point(440, y + 2), AutoSize = true };
        _vjoyLabel = vjoyRight;
        Controls.Add(secLabel2);
        Controls.Add(vjoyRight);
        y += 22;
        
        int barW = 360;
        int labelX = pad;
        int valX = pad + 68;
        int barX = pad + 120;
        int rowH = 26;
        
        // Steering
        var strLabel = new Label { Text = "STEERING", Font = FntInput, ForeColor = TextDim, Location = new Point(labelX, y + 4), AutoSize = true };
        _strVal = new Label { Text = "0.000", Font = FntVal, ForeColor = TextMain, Location = new Point(valX, y + 3), AutoSize = true };
        var strBarBg = CreateBarBg(barW); strBarBg.Location = new Point(barX, y + 7);
        _strBarFg = CreateBarFill(Accent, strBarBg);
        Controls.AddRange(new Control[] { strLabel, _strVal, strBarBg });
        y += rowH;
        
        // Gas
        var gasLabel = new Label { Text = "GAS", Font = FntInput, ForeColor = Green, Location = new Point(labelX, y + 4), AutoSize = true };
        _gasVal = new Label { Text = "0%", Font = FntVal, ForeColor = TextMain, Location = new Point(valX, y + 3), AutoSize = true };
        var gasBarBg = CreateBarBg(barW); gasBarBg.Location = new Point(barX, y + 7);
        _gasBarFg = CreateBarFill(Green, gasBarBg);
        Controls.AddRange(new Control[] { gasLabel, _gasVal, gasBarBg });
        y += rowH;
        
        // Brake
        var brkLabel = new Label { Text = "BRAKE", Font = FntInput, ForeColor = Red, Location = new Point(labelX, y + 4), AutoSize = true };
        _brkVal = new Label { Text = "0%", Font = FntVal, ForeColor = TextMain, Location = new Point(valX, y + 3), AutoSize = true };
        var brkBarBg = CreateBarBg(barW); brkBarBg.Location = new Point(barX, y + 7);
        _brkBarFg = CreateBarFill(Red, brkBarBg);
        Controls.AddRange(new Control[] { brkLabel, _brkVal, brkBarBg });
        y += rowH;
        
        // Clutch
        var cluLabel = new Label { Text = "CLUTCH", Font = FntInput, ForeColor = Yellow, Location = new Point(labelX, y + 4), AutoSize = true };
        _cluVal = new Label { Text = "0%", Font = FntVal, ForeColor = TextMain, Location = new Point(valX, y + 3), AutoSize = true };
        var cluBarBg = CreateBarBg(barW); cluBarBg.Location = new Point(barX, y + 7);
        _cluBarFg = CreateBarFill(Yellow, cluBarBg);
        Controls.AddRange(new Control[] { cluLabel, _cluVal, cluBarBg });
        y += rowH + 4;
        
        // Info row: Buttons, Packets, vJoy
        _buttonsLabel = new Label { Text = "Buttons: --", Font = FntInfo, ForeColor = TextDim, Location = new Point(pad, y), AutoSize = true };
        _packetLabel = new Label { Text = "--/s", Font = FntInfoSm, ForeColor = Green, Location = new Point(280, y), AutoSize = true };
        Controls.AddRange(new Control[] { _buttonsLabel, _packetLabel });
        y += 24;
        
        // ===== LOG section =====
        var secLabel3 = new Label { Text = "LOG", Font = FntSection, ForeColor = SectionLabel, Location = new Point(pad, y), AutoSize = true };
        Controls.Add(secLabel3);
        y += 16;
        
        _logBox = new TextBox
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Location = new Point(pad, y), Width = 508, Height = 100,
            BackColor = BgLog, ForeColor = TextDim,
            BorderStyle = BorderStyle.None, Font = FntLog
        };
        Controls.Add(_logBox);
    }
    
    private void OnDeviceFound(DiscoveredDevice device)
    {
        if (InvokeRequired) { BeginInvoke(() => OnDeviceFound(device)); return; }
        var text = $"{device.DeviceName} ({device.IpAddress})";
        if (!_deviceList.Items.Cast<string>().Any(s => s.Contains(device.IpAddress)))
            _deviceList.Items.Add(text);
    }
    
    private void OnInputReceived() { _packetCount++; }
    
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
        float str = s.NormalizedSteering;
        _strVal.Text = $"{str:F3}";
        _strVal.ForeColor = Math.Abs(str) > 0.01f ? Accent : TextMain;
        _strBarFg.Width = Math.Min((int)(Math.Abs(str) * 360), 360);
        _strBarFg.BackColor = Accent;
        
        // Gas
        float gas = s.NormalizedThrottle;
        _gasVal.Text = $"{(int)(gas * 100)}%";
        _gasVal.ForeColor = gas > 0.01f ? Green : TextMain;
        _gasBarFg.Width = (int)(gas * 360);
        _gasBarFg.BackColor = Green;
        
        // Brake
        float brk = s.NormalizedBrake;
        _brkVal.Text = $"{(int)(brk * 100)}%";
        _brkVal.ForeColor = brk > 0.01f ? Red : TextMain;
        _brkBarFg.Width = (int)(brk * 360);
        _brkBarFg.BackColor = Red;
        
        // Clutch
        float clu = s.NormalizedClutch;
        _cluVal.Text = $"{(int)(clu * 100)}%";
        _cluVal.ForeColor = clu > 0.01f ? Yellow : TextMain;
        _cluBarFg.Width = (int)(clu * 360);
        _cluBarFg.BackColor = Yellow;
        
        // Buttons — vJoy numbers
        var pressed = new List<string>();
        for (int i = 0; i < 16; i++)
            if (s.ButtonStates[i]) pressed.Add($"{(i + 1)}");
        _buttonsLabel.Text = pressed.Count > 0
            ? $"Buttons: {string.Join(", ", pressed)}"
            : "Buttons: --";
        _buttonsLabel.ForeColor = pressed.Count > 0 ? Accent : TextDim;
        
        // Packets
        _packetsPerSecond = _packetCount - _lastPacketCount;
        _lastPacketCount = _packetCount;
        if (_input.IsConnected)
        {
            _packetLabel.Text = $"{_packetsPerSecond * 10}/s";
            _packetLabel.ForeColor = _packetsPerSecond * 10 > 50 ? Green : Yellow;
        }
        else { _packetLabel.Text = "--/s"; _packetLabel.ForeColor = TextMuted; }
        
        // vJoy
        _vjoyLabel.Text = _vJoy.IsAvailable ? "vJoy: Active (Device 1)" : "vJoy: Not installed";
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