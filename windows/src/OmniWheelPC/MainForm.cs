using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using OmniWheelPC.Network;

namespace OmniWheelPC.UI;

public class MainForm : Form
{
    // Native Win32 drag constants
    private const int WM_NCLBUTTONDOWN = 0xA1;
    private const int HT_CAPTION = 0x2;
    private const int WM_GETMINMAXINFO = 0x24;
    private const int WM_NCHITTEST = 0x84;
    private const int HTCLIENT = 0x1;
    private const int HTLEFT = 10, HTRIGHT = 11, HTTOP = 12, HTTOPLEFT = 13;
    private const int HTTOPRIGHT = 14, HTBOTTOM = 15, HTBOTTOMLEFT = 16, HTBOTTOMRIGHT = 17;

    [DllImport("user32.dll")]
    private static extern int SendMessage(IntPtr hWnd, int Msg, int wParam, int lParam);

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateRoundRectRgn(int x1, int y1, int x2, int y2, int cx, int cy);

    [DllImport("user32.dll")]
    private static extern int SetWindowRgn(IntPtr hWnd, IntPtr hRgn, bool bRedraw);

    [System.Runtime.InteropServices.DllImport("winmm.dll")]
    private static extern uint timeBeginPeriod(uint wPeriod);

    [System.Runtime.InteropServices.DllImport("winmm.dll")]
    private static extern uint timeEndPeriod(uint wPeriod);

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    private readonly DiscoveryServer _discovery;
    private readonly InputReceiver _input;
    private readonly VJoyController _vJoy;
    private readonly System.Windows.Forms.Timer _uiTimer;

    // Theme Colors (Matching mockup image exactly)
    private static readonly Color BgDark = Color.FromArgb(11, 15, 25);         // #0B0F19
    private static readonly Color BgCard = Color.FromArgb(21, 27, 46);         // #151B2E
    private static readonly Color BgCardInner = Color.FromArgb(12, 16, 29);    // #0C101D
    private static readonly Color BorderColor = Color.FromArgb(30, 38, 64);    // #1E2640
    private static readonly Color AccentBlue = Color.FromArgb(37, 99, 235);    // #2563EB
    private static readonly Color AccentGlow = Color.FromArgb(59, 130, 246);   // #3B82F6
    private static readonly Color GreenDot = Color.FromArgb(34, 197, 94);      // #22C55E
    private static readonly Color TextWhite = Color.FromArgb(241, 245, 249);   // #F1F5F9
    private static readonly Color TextMuted = Color.FromArgb(148, 163, 184);  // #94A3B8
    private static readonly Color TextDim = Color.FromArgb(100, 116, 139);     // #64748B

    // Fonts
    private static readonly Font FntTitle = new("Segoe UI", 16f, FontStyle.Bold);
    private static readonly Font FntNav = new("Segoe UI", 10f, FontStyle.Bold);
    private static readonly Font FntHeader = new("Segoe UI", 18f, FontStyle.Regular);
    private static readonly Font FntCardTitle = new("Segoe UI", 11f, FontStyle.Bold);
    private static readonly Font FntInfo = new("Segoe UI", 9.5f, FontStyle.Regular);
    private static readonly Font FntLog = new("Cascadia Code", 8.5f);

    // Layout Controls
    private Panel _headerPanel = null!;
    private Panel _navPillPanel = null!;
    private Button _tabDashboardBtn = null!;
    private Button _tabEditorBtn = null!;
    private Button _btnMin = null!;
    private Button _btnMax = null!;
    private Button _btnClose = null!;

    // Main Container Panels
    private Panel _dashboardView = null!;
    private Panel _editorView = null!;

    // Dashboard View Components
    private Label _previewTitle = null!;
    private HudPreviewControl _hudPreview = null!;
    private Label _lblSyncProgress = null!;
    private Panel _connectedCard = null!;
    private Panel _logCard = null!;

    // Connected Info Labels
    private Label _connHeaderLabel = null!;
    private Label _lblDeviceName = null!;
    private Label _lblPhoneIp = null!;
    private Label _lblBattery = null!;
    private Label _lblPing = null!;
    private PingGraphControl _pingGraph = null!;
    private Label _lblSteeringRange = null!;

    // USB debugging bridge (adb reverse)
    private Panel _usbCard = null!;
    private Button _btnUsbToggle = null!;
    private Label _lblUsbStatus = null!;
    private Label _lblUsbList = null!;
    private readonly AdbReverseRunner _adbRunner;

    // Local IPv4 list shown on the connected card
    private Label _lblLanIp = null!;
    private DateTime _lastIpRefreshUtc;

    // Terminal Log Box
    private TextBox _logBox = null!;

    // HUD Editor View Components
    private TextBox _editorJsonBox = null!;
    private Button _btnApplyJson = null!;
    private Button _btnResetJson = null!;
    private Button _btnCopyJson = null!;
    private Label _editorStatusLabel = null!;

    // State
    private int _packetCount;
    private int _packetsPerSecond;
    private DateTime _lastPpsTime = DateTime.UtcNow;
    private int _lastPpsCount = 0;
    private bool _cleanedUp;
    private string _currentLayoutJson = "";
    private DateTime _lastRenderedInput = DateTime.MinValue;
    private bool _syncProgressShown;
    private bool _dwmRounded;
    private long _lastPingSampleMs = -1;
    private double _smoothedLatency = -1;

    public MainForm()
    {
        Text = "OmniWheel PC";
        ClientSize = new Size(1100, 640);
        MinimumSize = new Size(880, 520);
        BackColor = BgDark;
        // Truly frameless: no native caption/border can ever be drawn (avoids
        // the classic Windows frame). WS_THICKFRAME + WM_NCHITTEST below keep
        // edge/corner resizing working.
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterScreen;
        DoubleBuffered = true;

        _currentLayoutJson = HudLayoutManager.DefaultLayoutJson;

        _discovery = new DiscoveryServer { DeviceName = Environment.MachineName };
        _discovery.OnDeviceFound += OnDeviceFound;
        _discovery.OnLog += Log;

        _input = new InputReceiver();
        _input.OnInputReceived += OnInputReceived;
        _input.OnConnected += OnDeviceConnected;
        _input.OnDisconnected += OnDeviceDisconnected;
        _input.OnLog += Log;
        _input.OnMetaReceived += OnMetaReceived;
        _input.OnWidgetReceived += OnWidgetReceived;

        _adbRunner = new AdbReverseRunner(null, () => _input.IsUsbConnection);
        _adbRunner.OnLog += Log;

        _vJoy = new VJoyController();
        _vJoy.OnLog += Log;

        _uiTimer = new System.Windows.Forms.Timer { Interval = 1 }; // ~1ms (drives high-refresh preview)
        _uiTimer.Tick += UpdateUI;

        BuildUI();
        Resize += (_, _) =>
        {
            Relayout();
            if (!_dwmRounded) ApplyRoundedRegion();
        };

        Load += (s, e) =>
        {
            timeBeginPeriod(1); // allow sub-16ms timers for high-refresh preview
            ApplyRoundedCorners();
            _vJoy.Initialize();
            _discovery.Start();
            _input.Start();
            _uiTimer.Start();
            Log("OmniWheel PC v0.9.13 started");
            Log("Ready for high-performance UDP communication on ports 19700/19701");
        };

        // If the receiver is opened while the phone is already connected, the
        // connection event may have fired before we could subscribe, so ask for
        // the layout once on startup too.
        Shown += (s, e) =>
        {
            if (_input.IsConnected) _input.RequestLayoutSync();
        };

        FormClosing += (s, e) =>
        {
            timeEndPeriod(1);
            Cleanup();
        };
    }

    private void Cleanup()
    {
        if (_cleanedUp) return;
        _cleanedUp = true;
        try
        {
            _uiTimer.Stop();
            _discovery.Dispose();
        }
        catch { }
        try { _input.Dispose(); } catch { }
        try { _vJoy.Dispose(); } catch { }
    }

    private void BuildUI()
    {
        Controls.Clear();

        // 1. HEADER / TITLEBAR
        _headerPanel = new Panel
        {
            Height = 56,
            Dock = DockStyle.Top,
            BackColor = BgDark
        };
        _headerPanel.MouseDown += Header_MouseDown;

        // Top Left Logo & Title
        var logoIcon = new PictureBox
        {
            Size = new Size(36, 36),
            Location = new Point(16, 10),
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.Transparent
        };
        logoIcon.Paint += (s, pe) =>
        {
            var g = pe.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using var phonePen = new Pen(TextWhite, 2f);
            var rect = new Rectangle(6, 2, 24, 32);
            g.DrawRoundedRectangle(phonePen, rect, 6);
            using var wheelPen = new Pen(AccentGlow, 2f);
            g.DrawEllipse(wheelPen, 11, 10, 14, 14);
        };
        logoIcon.MouseDown += Header_MouseDown;

        var titleLabel = new Label
        {
            Text = "OmniWheel v0.9.13",
            Font = FntTitle,
            ForeColor = TextWhite,
            AutoSize = true,
            Location = new Point(58, 12),
            BackColor = Color.Transparent
        };
        titleLabel.MouseDown += Header_MouseDown;

        _headerPanel.Controls.Add(logoIcon);
        _headerPanel.Controls.Add(titleLabel);

        // Center Navigation Pill ([Dashboard] HUD Editor)
        _navPillPanel = new Panel
        {
            Size = new Size(220, 36),
            BackColor = Color.FromArgb(16, 22, 38)
        };
        _navPillPanel.Paint += (s, pe) =>
        {
            pe.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            using var path = GetRoundedPath(_navPillPanel.ClientRectangle, 18);
            using var borderPen = new Pen(BorderColor, 1f);
            pe.Graphics.DrawPath(borderPen, path);
        };

        _tabDashboardBtn = new Button
        {
            Text = "Dashboard",
            Font = FntNav,
            ForeColor = TextWhite,
            BackColor = AccentBlue,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(106, 30),
            Location = new Point(3, 3),
            Cursor = Cursors.Hand
        };
        _tabDashboardBtn.FlatAppearance.BorderSize = 0;
        _tabDashboardBtn.Click += (_, _) => SwitchTab(true);

        _tabEditorBtn = new Button
        {
            Text = "HUD Editor",
            Font = FntNav,
            ForeColor = TextMuted,
            BackColor = Color.Transparent,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(106, 30),
            Location = new Point(111, 3),
            Cursor = Cursors.Hand
        };
        _tabEditorBtn.FlatAppearance.BorderSize = 0;
        _tabEditorBtn.Click += (_, _) => SwitchTab(false);

        _navPillPanel.Controls.Add(_tabDashboardBtn);
        _navPillPanel.Controls.Add(_tabEditorBtn);
        _headerPanel.Controls.Add(_navPillPanel);

        // Top Right Window Controls (-, [], x)
        _btnClose = CreateHeaderBtn("\u2715", Color.FromArgb(239, 68, 68)); // ✕
        _btnClose.Click += (_, _) => Close();

        _btnMax = CreateHeaderBtn("\uE922", TextMuted); // maximize glyph (Segoe MDL2)
        _btnMax.Click += (_, _) =>
        {
            WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;
            UpdateMaxBtnGlyph();
        };

        _btnMin = CreateHeaderBtn("\u2014", TextMuted); // —
        _btnMin.Click += (_, _) => WindowState = FormWindowState.Minimized;

        _btnMax.Font = new Font("Segoe MDL2 Assets", 10f);
        _headerPanel.Controls.Add(_btnClose);
        _headerPanel.Controls.Add(_btnMax);
        _headerPanel.Controls.Add(_btnMin);

        Controls.Add(_headerPanel);

        // 2. VIEWS (DASHBOARD & EDITOR)
        _dashboardView = new Panel { Dock = DockStyle.Fill, BackColor = BgDark };
        _editorView = new Panel { Dock = DockStyle.Fill, BackColor = BgDark, Visible = false };

        BuildDashboardView();
        BuildEditorView();

        Controls.Add(_dashboardView);
        Controls.Add(_editorView);

        Relayout();
    }

    private void UpdateMaxBtnGlyph()
    {
        _btnMax.Text = WindowState == FormWindowState.Maximized ? "\uE923" : "\uE922";
    }

    private Button CreateHeaderBtn(string text, Color color)
    {
        var btn = new Button
        {
            Text = text,
            Font = new Font("Segoe UI", 11f, FontStyle.Bold),
            ForeColor = color,
            BackColor = Color.Transparent,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(36, 32),
            Cursor = Cursors.Hand
        };
        btn.FlatAppearance.BorderSize = 0;
        btn.FlatAppearance.MouseOverBackColor = Color.FromArgb(30, 40, 65);
        return btn;
    }

    private void BuildDashboardView()
    {
        _dashboardView.Controls.Clear();

        // Preview Section Title
        _previewTitle = new Label
        {
            Text = "Preview",
            Font = FntHeader,
            ForeColor = TextMuted,
            AutoSize = true,
            Location = new Point(20, 10)
        };
        _dashboardView.Controls.Add(_previewTitle);

        // Preview Control
        _hudPreview = new HudPreviewControl
        {
            Location = new Point(20, 48),
            Widgets = HudLayoutManager.LoadLayout(_currentLayoutJson)
        };

        // Sync Progress bar overlay label
        _lblSyncProgress = new Label
        {
            Text = "Receiving current layout... [\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588] 100%",
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            ForeColor = AccentGlow,
            BackColor = Color.FromArgb(16, 22, 38),
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleCenter,
            Visible = false
        };
        _dashboardView.Controls.Add(_lblSyncProgress);
        _dashboardView.Controls.Add(_hudPreview);

        // Connected Info Card
        _connectedCard = new Panel { BackColor = BgCard };
        _connectedCard.Paint += (s, pe) => DrawCardBorder(s, pe);

        _connHeaderLabel = new Label
        {
            Text = "Waiting for connection...",
            Font = FntCardTitle,
            ForeColor = TextMuted,
            AutoSize = true,
            Location = new Point(16, 14)
        };

        _lblDeviceName = CreateCardInfoLabel("connected device: --", 16, 44);
        _lblPhoneIp = CreateCardInfoLabel("Phone IP: --", 16, 68);
        _lblBattery = CreateCardInfoLabel("Battery: --", 16, 92);

        _lblPing = CreateCardInfoLabel("Current ping: -- ms", 16, 116);
        _pingGraph = new PingGraphControl { Size = new Size(90, 24), Location = new Point(165, 114) };

        _lblPing = CreateCardInfoLabel("Current ping: -- ms", 16, 116);
        _pingGraph = new PingGraphControl { Size = new Size(90, 24), Location = new Point(165, 114) };

        _lblSteeringRange = CreateCardInfoLabel("Steering range: 900 degree", 16, 144);
        _lblLanIp = CreateCardInfoLabel("LAN IPs: --", 16, 168);

        _connectedCard.Controls.AddRange(new Control[]
        {
            _connHeaderLabel, _lblDeviceName, _lblPhoneIp,
            _lblBattery, _lblPing, _pingGraph, _lblSteeringRange, _lblLanIp
        });
        _dashboardView.Controls.Add(_connectedCard);

        // USB Debugging Card - WiFi-free link over adb reverse
        _usbCard = new Panel { BackColor = BgCard };
        _usbCard.Paint += (s, pe) => DrawCardBorder(s, pe);

        var usbTitle = new Label
        {
            Text = "USB Connection (adb reverse)",
            Font = FntCardTitle,
            ForeColor = TextWhite,
            AutoSize = true,
            Location = new Point(16, 12)
        };

        _btnUsbToggle = new Button
        {
            Text = "ENABLE USB",
            Font = FntNav,
            ForeColor = TextWhite,
            BackColor = AccentBlue,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(120, 34),
            Cursor = Cursors.Hand,
            Location = new Point(16, 40)
        };
        _btnUsbToggle.FlatAppearance.BorderSize = 0;
        _btnUsbToggle.Click += (_, _) => ToggleUsb();

        _lblUsbStatus = new Label
        {
            Text = "Press ENABLE to accept a phone over USB debugging.",
            Font = FntInfo,
            ForeColor = TextMuted,
            AutoSize = true,
            Location = new Point(16, 86)
        };

        _lblUsbList = new Label
        {
            Text = "",
            Font = FntInfo,
            ForeColor = TextDim,
            AutoSize = true,
            Location = new Point(16, 106)
        };

        _usbCard.Controls.AddRange(new Control[]
        {
            usbTitle, _btnUsbToggle, _lblUsbStatus, _lblUsbList
        });
        _dashboardView.Controls.Add(_usbCard);

        // Terminal Log Card
        _logCard = new Panel { BackColor = BgCard };
        _logCard.Paint += (s, pe) => DrawCardBorder(s, pe);

        _logBox = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            BackColor = BgCardInner,
            ForeColor = Color.FromArgb(148, 163, 184),
            BorderStyle = BorderStyle.None,
            Font = FntLog,
            Location = new Point(12, 12)
        };
        _logCard.Controls.Add(_logBox);

        _dashboardView.Controls.Add(_logCard);
    }

    private Label CreateCardInfoLabel(string text, int x, int y)
    {
        return new Label
        {
            Text = text,
            Font = FntInfo,
            ForeColor = Color.FromArgb(203, 213, 225),
            AutoSize = true,
            Location = new Point(x, y)
        };
    }

    private void BuildEditorView()
    {
        _editorView.Controls.Clear();

        var lblTitle = new Label
        {
            Text = "HUD Layout Editor / JSON Configuration",
            Font = FntHeader,
            ForeColor = TextWhite,
            AutoSize = true,
            Location = new Point(20, 10)
        };

        var lblSub = new Label
        {
            Text = "View or customize the controller layout JSON. You can paste layout JSON exported from the phone, or reset to default.",
            Font = FntInfo,
            ForeColor = TextMuted,
            AutoSize = true,
            Location = new Point(20, 46)
        };

        _editorJsonBox = new TextBox
        {
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            BackColor = BgCardInner,
            ForeColor = Color.FromArgb(56, 189, 248), // Bright sky blue code
            BorderStyle = BorderStyle.FixedSingle,
            Font = new Font("Cascadia Code", 9.5f),
            Location = new Point(20, 76),
            Text = _currentLayoutJson
        };

        _btnApplyJson = new Button
        {
            Text = "Apply JSON Layout",
            Font = FntNav,
            ForeColor = TextWhite,
            BackColor = AccentBlue,
            FlatStyle = FlatStyle.Flat,
            Size = new Size(160, 36),
            Cursor = Cursors.Hand
        };
        _btnApplyJson.FlatAppearance.BorderSize = 0;
        _btnApplyJson.Click += (_, _) =>
        {
            try
            {
                var loaded = HudLayoutManager.LoadLayout(_editorJsonBox.Text);
                _currentLayoutJson = _editorJsonBox.Text;
                _hudPreview.Widgets = loaded;
                _editorStatusLabel.Text = "\u2713 Layout applied successfully!";
                _editorStatusLabel.ForeColor = GreenDot;
            }
            catch (Exception ex)
            {
                _editorStatusLabel.Text = $"\u2715 Error: {ex.Message}";
                _editorStatusLabel.ForeColor = Color.FromArgb(239, 68, 68);
            }
        };

        _btnResetJson = new Button
        {
            Text = "Reset to Default",
            Font = FntNav,
            ForeColor = TextWhite,
            BackColor = Color.FromArgb(51, 65, 85),
            FlatStyle = FlatStyle.Flat,
            Size = new Size(150, 36),
            Cursor = Cursors.Hand
        };
        _btnResetJson.FlatAppearance.BorderSize = 0;
        _btnResetJson.Click += (_, _) =>
        {
            _currentLayoutJson = HudLayoutManager.DefaultLayoutJson;
            _editorJsonBox.Text = _currentLayoutJson;
            _hudPreview.Widgets = HudLayoutManager.LoadLayout(_currentLayoutJson);
            _editorStatusLabel.Text = "\u2713 Reset to default layout!";
            _editorStatusLabel.ForeColor = GreenDot;
        };

        _btnCopyJson = new Button
        {
            Text = "Copy JSON",
            Font = FntNav,
            ForeColor = TextWhite,
            BackColor = Color.FromArgb(30, 41, 59),
            FlatStyle = FlatStyle.Flat,
            Size = new Size(120, 36),
            Cursor = Cursors.Hand
        };
        _btnCopyJson.FlatAppearance.BorderSize = 0;
        _btnCopyJson.Click += (_, _) =>
        {
            try
            {
                Clipboard.SetText(_editorJsonBox.Text);
                _editorStatusLabel.Text = "\u2713 Copied to clipboard!";
                _editorStatusLabel.ForeColor = GreenDot;
            }
            catch { }
        };

        _editorStatusLabel = new Label
        {
            Text = "",
            Font = FntInfo,
            ForeColor = GreenDot,
            AutoSize = true
        };

        _editorView.Controls.AddRange(new Control[]
        {
            lblTitle, lblSub, _editorJsonBox,
            _btnApplyJson, _btnResetJson, _btnCopyJson, _editorStatusLabel
        });
    }

    private void ToggleUsb()
    {
        if (_adbRunner.Enabled)
        {
            _adbRunner.Disable();
            // Tear down an existing USB session too — removing the adb reverse
            // mapping alone leaves the accepted TCP connection alive.
            _input.StopUsbSession();
            _btnUsbToggle.Text = "ENABLE USB";
            _btnUsbToggle.BackColor = AccentBlue;
            _lblUsbStatus.Text = "USB disabled. Press ENABLE to accept a phone over USB debugging.";
            _lblUsbList.Text = "";
            Log("USB bridge disabled");
        }
        else
        {
            _adbRunner.Enable();
            _btnUsbToggle.Text = "DISABLE USB";
            _btnUsbToggle.BackColor = Color.FromArgb(51, 65, 85);
            _lblUsbStatus.Text = "USB enabled — run the phone's 'Connect USB' and accept the debugging prompt.";
            Log("USB bridge enabled — adb reverse on localhost:" + OmniWheelPC.Network.Protocol.UsbBridgeTcpPort);
        }
    }

    private void SwitchTab(bool isDashboard)
    {
        _dashboardView.Visible = isDashboard;
        _editorView.Visible = !isDashboard;

        if (isDashboard)
        {
            _tabDashboardBtn.BackColor = AccentBlue;
            _tabDashboardBtn.ForeColor = TextWhite;
            _tabEditorBtn.BackColor = Color.Transparent;
            _tabEditorBtn.ForeColor = TextMuted;
        }
        else
        {
            _tabDashboardBtn.BackColor = Color.Transparent;
            _tabDashboardBtn.ForeColor = TextMuted;
            _tabEditorBtn.BackColor = AccentBlue;
            _tabEditorBtn.ForeColor = TextWhite;
        }
    }

    private void DrawCardBorder(object? sender, PaintEventArgs pe)
    {
        if (sender is Panel p)
        {
            pe.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            var r = p.ClientRectangle;
            r.Width -= 1;
            r.Height -= 1;
            using var path = GetRoundedPath(r, 12);
            using var borderPen = new Pen(BorderColor, 1.5f);
            pe.Graphics.DrawPath(borderPen, path);
        }
    }

    private static GraphicsPath GetRoundedPath(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        if (r.Width <= 1 || r.Height <= 1) { path.AddRectangle(r); return path; }
        int d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        return path;
    }

    private void Header_MouseDown(object? sender, MouseEventArgs e)
    {
        if (e.Button == MouseButtons.Left)
        {
            ReleaseCapture();
            SendMessage(Handle, WM_NCLBUTTONDOWN, HT_CAPTION, 0);
        }
    }

    /// <summary>
    /// Strip the caption-related window styles so Windows/DWM can never draw a
    /// title bar or caption strip over our custom header (the light-blue bar
    /// seen on frameless forms). WS_BORDER/WS_DLGFRAME/WS_SYSMENU/WS_CAPTION all
    /// go; resizing is handled entirely by WM_NCHITTEST, so WS_THICKFRAME is not
    /// needed either (keeping it is what lets DWM paint the ghost caption).
    /// </summary>
    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.Style &= ~0x00C00000; // WS_CAPTION (WS_BORDER | WS_DLGFRAME)
            cp.Style &= ~0x00800000; // WS_BORDER
            cp.Style &= ~0x00040000; // WS_DLGFRAME
            cp.Style &= ~0x00080000; // WS_SYSMENU
            cp.Style &= ~0x00020000; // WS_MINIMIZEBOX
            cp.Style &= ~0x00010000; // WS_MAXIMIZEBOX
            cp.ExStyle &= ~0x00000080; // WS_EX_DLGMODALFRAME
            // NOTE: WS_THICKFRAME stays ON — it is what makes edge/corner
            // dragging, native resize cursors and Windows 11 snap layouts work
            // on a frameless form. The thick frame itself never shows: DWM
            // rendering is disabled, WM_NCCALCSIZE collapses the non-client area
            // to zero and WM_NCPAINT is short-circuited, so it cannot paint the
            // ghost caption strip.
            return cp;
        }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int attrValue, int attrSize);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
    private const uint SWP_NOZORDER = 0x0004;
    private const uint SWP_NOACTIVATE = 0x0010;
    private const uint SWP_FRAMECHANGED = 0x0020;
    private const uint SWP_NOOWNERZORDER = 0x0200;
    private const uint SWP_NOMOVE = 0x0002;
    private const uint SWP_NOSIZE = 0x0001;

    /// <summary>
    /// Round the window corners so they match the app's soft card edges.
    /// DWM's rounded-corner attribute was abandoned: on Windows 11 it lets the
    /// shell draw a light caption/border strip at the top of the window. The
    /// region-based approach is fully client-side, so no native strip can ever
    /// appear.
    /// </summary>
    private void ApplyRoundedCorners()
    {
        _dwmRounded = false;
        if (!IsHandleCreated) return;

        // DWMWA_NCRENDERING_POLICY = 2, DWMNCRP_DISABLED = 1 — stop DWM from
        // drawing ANY non-client chrome (caption glow, snap-adjusted border).
        int disabled = 1;
        DwmSetWindowAttribute(Handle, 2, ref disabled, sizeof(int));

        // DWMWA_WINDOW_CORNER_PREFERENCE = 33, DWMWCP_DONOTROUND = 1 — stop
        // Windows 11 from drawing its own rounded light border around the
        // window (another source of the phantom top strip). We do our own
        // rounding via the region, so DWM rounding is unnecessary.
        int donotround = 1;
        DwmSetWindowAttribute(Handle, 33, ref donotround, sizeof(int));

        // Force Windows to re-run the frame layout with the stripped styles, so
        // any caption rect DWM cached at create time is dropped for good.
        SetWindowPos(Handle, IntPtr.Zero, 0, 0, 0, 0,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED | SWP_NOOWNERZORDER | SWP_NOMOVE | SWP_NOSIZE);

        ApplyRoundedRegion();
    }

    private void ApplyRoundedRegion()
    {
        if (IsDisposed || !IsHandleCreated) return;
        int r = Math.Max(8, Math.Min(18, Height / 4));
        IntPtr region = CreateRoundRectRgn(0, 0, Width + 1, Height + 1, r, r);
        if (region != IntPtr.Zero)
        {
            SetWindowRgn(Handle, region, true);
        }
    }

    /// <summary>
    /// Keep maximized frameless window within the monitor's working area so it
    /// respects the taskbar instead of covering the whole screen, eliminate the
    /// native white caption/border line via WM_NCCALCSIZE, and make the window
    /// resizable by dragging its edges/corners.
    /// </summary>
    protected override void WndProc(ref Message m)
    {
        const int WM_NCCALCSIZE = 0x0083;
        if (m.Msg == WM_NCCALCSIZE && m.WParam == (IntPtr)1)
        {
            m.Result = IntPtr.Zero;
            return;
        }

        // Never let Windows paint the caption/frame ourselves: skip everything
        // the non-client area would normally do. This is the WinForms
        // equivalent of AppWindow.TitleBar.ExtendsContentIntoTitleBar = true
        // (that WinAppSDK API only exists for WinUI 3, not WinForms).
        const int WM_NCPAINT = 0x0085;
        const int WM_NCACTIVATE = 0x0086;
        if (m.Msg == WM_NCPAINT)
        {
            m.Result = IntPtr.Zero;
            return;
        }
        if (m.Msg == WM_NCACTIVATE)
        {
            // Return "true": tells the system the window is drawn activated
            // and stops it from re-rendering the caption/frame on activation.
            m.Result = (IntPtr)1;
            return;
        }

        if (m.Msg == WM_GETMINMAXINFO)
        {
            var sc = Screen.FromHandle(Handle).WorkingArea;
            // MINMAXINFO: ptMaxSize, ptMaxPosition, ptMinTrackSize
            var info = (MINMAXINFO)System.Runtime.InteropServices.Marshal.PtrToStructure(
                m.LParam, typeof(MINMAXINFO))!;
            info.ptMaxSize.X = sc.Width;
            info.ptMaxSize.Y = sc.Height;
            info.ptMaxPosition.X = sc.X;
            info.ptMaxPosition.Y = sc.Y;
            System.Runtime.InteropServices.Marshal.StructureToPtr(info, m.LParam, false);
            m.Result = IntPtr.Zero;
            return;
        }

        if (m.Msg == WM_NCHITTEST)
        {
            // Compute the resize grip from the full window rect regardless of
            // what the default handler reports, so edge/corner dragging always
            // works even with the zeroed non-client area from WM_NCCALCSIZE.
            int lp = m.LParam.ToInt32();
            int sx = (short)(lp & 0xFFFF);
            int sy = (short)((lp >> 16) & 0xFFFF);
            var pt = PointToClient(new Point(sx, sy));

            const int edge = 9; // resize grip in px
            int w = Width, h = Height;
            if (pt.X >= -edge && pt.Y >= -edge && pt.X <= w + edge && pt.Y <= h + edge)
            {
                bool top = pt.Y <= edge, bottom = pt.Y >= h - edge;
                bool left = pt.X <= edge, right = pt.X >= w - edge;

                int hc = (top && left) ? HTTOPLEFT : (top && right) ? HTTOPRIGHT
                    : (bottom && left) ? HTBOTTOMLEFT : (bottom && right) ? HTBOTTOMRIGHT
                    : top ? HTTOP : bottom ? HTBOTTOM
                    : left ? HTLEFT : right ? HTRIGHT : HTCLIENT;

                m.Result = (IntPtr)hc;
                return;
            }

            base.WndProc(ref m);
            return;
        }

        base.WndProc(ref m);
    }

    [System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)]
    private struct MINMAXINFO
    {
        public System.Drawing.Point ptReserved;
        public System.Drawing.Point ptMaxSize;
        public System.Drawing.Point ptMaxPosition;
        public System.Drawing.Point ptMinTrackSize;
        public System.Drawing.Point ptMaxTrackSize;
    }

    private void Relayout()
    {
        int w = ClientSize.Width;
        int h = ClientSize.Height;

        // Position Nav Pill in top center
        _navPillPanel.Location = new Point((w - _navPillPanel.Width) / 2, 10);

        // Position Window Controls top right
        _btnClose.Location = new Point(w - 42, 10);
        _btnMax.Location = new Point(w - 82, 10);
        _btnMin.Location = new Point(w - 122, 10);

        // Dashboard View Layout
        int contentW = w - 40;
        int contentH = h - 56 - 20;

        int leftW = (int)(contentW * 0.64f);
        int rightW = contentW - leftW - 16;

        _hudPreview.Size = new Size(leftW, contentH - 48);
        _lblSyncProgress.Bounds = new Rectangle(20, 48 + _hudPreview.Height - 36, leftW, 32);

        int rightX = 20 + leftW + 16;
        _connectedCard.Bounds = new Rectangle(rightX, 48, rightW, 196);

        // USB Card between connected card and log card
        int usbY = 48 + 196 + 16;
        int usbH = 120;
        _usbCard.Bounds = new Rectangle(rightX, usbY, rightW, usbH);

        int logY = usbY + usbH + 16;
        int logH = contentH - logY + 20;
        _logCard.Bounds = new Rectangle(rightX, logY, rightW, Math.Max(100, logH));
        _logBox.Size = new Size(rightW - 24, Math.Max(80, logH - 24));

        // Editor View Layout
        _editorJsonBox.Size = new Size(w - 40, contentH - 140);
        int btnY = 76 + (contentH - 140) + 12;
        _btnApplyJson.Location = new Point(20, btnY);
        _btnResetJson.Location = new Point(190, btnY);
        _btnCopyJson.Location = new Point(350, btnY);
        _editorStatusLabel.Location = new Point(480, btnY + 8);
    }

    private void OnDeviceFound(DiscoveredDevice device)
    {
        if (InvokeRequired) { BeginInvoke(() => OnDeviceFound(device)); return; }
    }

    private void OnInputReceived()
    {
        _packetCount++;
        _hudPreview.InputState = _input.CurrentState;

        // Push straight to the vJoy device on the receiver thread. This runs at
        // the real packet rate (hundreds of Hz) instead of the ~64Hz WinForms
        // WM_TIMER rate, so the game sees the same smooth, immediate axis
        // updates the preview shows - no added wheel/gear lag.
        if (_vJoy.IsAvailable)
            _vJoy.Update(_input.CurrentState);
    }

    private void OnDeviceConnected()
    {
        if (InvokeRequired) { BeginInvoke(OnDeviceConnected); return; }
        _connHeaderLabel.Text = "Connected *";
        _connHeaderLabel.ForeColor = GreenDot;

        // Start from an EMPTY preview so only the layout the phone actually
        // sends is shown (a stale/default layout must never linger, e.g. a
        // clutch widget the phone no longer uses).
        _hudPreview.Widgets = new List<HudWidget>();
        _metaLogged = false;
        _syncProgressShown = false;
        Log("Device connected - clearing preview, waiting for phone layout...");

        // Ask the phone for its meta + full layout right now so the preview
        // fills in immediately instead of waiting for the phone's next
        // periodic sync (which can be up to 30s away).
        _input.RequestLayoutSync();

        string devIp = _input.ConnectedDeviceIp ?? "Unknown";
        _lblDeviceName.Text = "connected device: --";
        _lblPhoneIp.Text = _input.IsUsbConnection ? "Phone: USB connection (adb reverse)" : $"Phone IP: {devIp}";
        _lblBattery.Text = "Battery: --";
    }

    private bool _metaLogged;
    private void OnMetaReceived()
    {
        if (InvokeRequired) { BeginInvoke(OnMetaReceived); return; }
        var s = _input.CurrentState;

        string name = string.IsNullOrWhiteSpace(s.PhoneDeviceName) ? "Android Phone" : s.PhoneDeviceName;
        _lblDeviceName.Text = $"connected device: {name}";

        if (s.PhoneBatteryPercent != 255)
            _lblBattery.Text = $"Battery: {s.PhoneBatteryPercent}%";

        _lblSteeringRange.Text = $"Steering range: {s.PhoneMaxAngle} degree";
        _hudPreview.SteeringMaxAngle = s.PhoneMaxAngle;
        _hudPreview.InputState = s;
        _hudPreview.Invalidate();

        if (!_metaLogged)
        {
            _metaLogged = true;
            Log($"Synced from phone: {name} | battery {s.PhoneBatteryPercent}% | steering {s.PhoneMaxAngle}\u00B0 | clutch {(s.ClutchEnabled ? "ON" : "OFF")} | {s.PhoneScreenWidthPx}x{s.PhoneScreenHeightPx}px");
        }
    }

    private void OnWidgetReceived(string json)
    {
        if (InvokeRequired) { BeginInvoke(() => OnWidgetReceived(json)); return; }

        if (json.StartsWith("FULL:"))
        {
            ShowSyncProgress();
            var layoutJson = json.Substring(5);
            var loaded = HudLayoutManager.LoadLayout(layoutJson);
            _hudPreview.Widgets = loaded;
            try
            {
                // Definitive diagnostic: dump what the phone sent so any future
                // layout mismatch is instantly verifiable from this file.
                System.IO.File.WriteAllText(
                    System.IO.Path.Combine(System.IO.Path.GetTempPath(), "omniwheel_last_layout.json"),
                    layoutJson);
            }
            catch { }
            if (_lblSteeringRange != null)
            {
                string sample = loaded.Count > 0 ? $" | first={loaded[0].Id} cx={loaded[0].Cx} cy={loaded[0].Cy}" : "";
                Log("HUD layout received from phone (" + loaded.Count + " widgets)" + sample);
            }
        }
        else
        {
            if (json.Contains("\"remove\"", StringComparison.OrdinalIgnoreCase) && json.Contains("\"remove\":true", StringComparison.OrdinalIgnoreCase))
            {
                var id = ExtractId(json);
                if (id != null) { _hudPreview.RemoveWidget(id); Log("HUD widget removed: " + id); }
            }
            else
            {
                var w = HudLayoutManager.ParseSingleWidget(json);
                if (w != null)
                {
                    _hudPreview.UpsertWidget(w);
                    Log("HUD widget update: " + w.Id);
                }
            }
        }
    }

    private static string? ExtractId(string json)
    {
        try
        {
            var doc = System.Text.Json.JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty("id", out var id) && id.ValueKind == System.Text.Json.JsonValueKind.String)
                return id.GetString();
        }
        catch { }
        return null;
    }

    private void OnDeviceDisconnected()
    {
        if (InvokeRequired) { BeginInvoke(OnDeviceDisconnected); return; }
        _connHeaderLabel.Text = "Waiting for phone...";
        _connHeaderLabel.ForeColor = TextMuted;
        _lblDeviceName.Text = "connected device: --";
        _lblPhoneIp.Text = "Phone IP: --";
        _lblSteeringRange.Text = "Steering range: --";
        _lblPing.Text = "Current ping: -- ms";
    }

    private void ShowSyncProgress()
    {
        if (InvokeRequired) { BeginInvoke(ShowSyncProgress); return; }
        // Only flash once per connection (the phone re-sends the full layout
        // several times right after connecting, which used to flicker 5x).
        if (_syncProgressShown) return;
        _syncProgressShown = true;
        _lblSyncProgress.Text = "Receiving current layout... [\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588] 100%";
        _lblSyncProgress.Visible = true;
        Task.Run(async () =>
        {
            await Task.Delay(1500);
            if (!IsDisposed && _lblSyncProgress.IsHandleCreated)
            {
                BeginInvoke(() => { _lblSyncProgress.Visible = false; });
            }
        });
    }

    private void UpdateUI(object? sender, EventArgs e)
    {
        _hudPreview.IsConnected = _input.IsConnected;

        // Refresh local IPv4 list every 5s (hotspot toggles, adapters change).
        if (_lastIpRefreshUtc == default || (DateTime.UtcNow - _lastIpRefreshUtc).TotalSeconds >= 5)
        {
            _lastIpRefreshUtc = DateTime.UtcNow;
            var ips = OmniWheelPC.Network.LocalAddresses.GetIpv4Preferred();
            if (ips.Count == 0) _lblLanIp.Text = "LAN IPs: --";
            else _lblLanIp.Text = "LAN IPs: " + string.Join(" | ", ips);
        }

        // USB status line reflects the live bridge state.
        if (_lblUsbList != null)
        {
            _lblUsbList.Text = _input.IsUsbConnection
                ? "\u25CF Connected over USB (adb reverse)"
                : (_adbRunner.Enabled ? "Waiting for USB phone\u2026 unplug the cable or accept the prompt." : "");
        }

        var now = DateTime.UtcNow;
        var elapsedSec = (now - _lastPpsTime).TotalSeconds;
        if (elapsedSec >= 0.5)
        {
            _packetsPerSecond = (int)((_packetCount - _lastPpsCount) / elapsedSec);
            _lastPpsCount = _packetCount;
            _lastPpsTime = now;
        }

        // Redraw the live preview whenever fresh input arrived, so the wheel
        // rotates at a rate matching the running display refresh (not capped at 60).
        if (_input.CurrentState.Timestamp != _lastRenderedInput)
        {
            _lastRenderedInput = _input.CurrentState.Timestamp;
            _hudPreview.Invalidate();
        }

        if (_input.IsConnected)
        {
            // Sample latency at 4 Hz and smooth it with an EMA so the number
            // settles instead of flickering with every packet burst.
            long nowMs = now.Ticks / TimeSpan.TicksPerMillisecond;
            if (_lastPingSampleMs < 0 || nowMs - _lastPingSampleMs >= 250)
            {
                _lastPingSampleMs = nowMs;
                double raw = _input.CurrentState.LatencyMs;
                _smoothedLatency = _smoothedLatency < 0 ? raw : (_smoothedLatency * 0.7 + raw * 0.3);
                _lblPing.Text = $"Latency: {FormatLatencyMs(_smoothedLatency)} ms";
                _pingGraph.AddPing((int)Math.Round(_smoothedLatency));
            }
        }
        else
        {
            _smoothedLatency = -1;
            _lblPing.Text = "Latency: -- ms";
        }
    }

    /// <summary>
    /// Sub-millisecond real pings are genuinely achievable on a fast local link
    /// (WiFi LAN or USB), but they must never display as the impossible "0 ms".
    /// Under 1ms we show two decimals ("0.98 ms"); at 1ms+ show the rounded
    /// integer as before.
    /// </summary>
    private static string FormatLatencyMs(double ms)
    {
        if (ms < 1.0) return ms.ToString("0.00");
        return ((long)Math.Round(ms)).ToString();
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

// Graphics extension helper for rounded rectangles
public static class GraphicsExtensions
{
    public static void DrawRoundedRectangle(this Graphics g, Pen pen, Rectangle r, int radius)
    {
        using var path = new GraphicsPath();
        int d = radius * 2;
        path.AddArc(r.X, r.Y, d, d, 180, 90);
        path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        path.CloseFigure();
        g.DrawPath(pen, path);
    }
}
