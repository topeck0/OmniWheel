using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;
using OmniWheelPC.Network;

namespace OmniWheelPC.UI;

public class HudPreviewControl : Control
{
    private List<HudWidget> _widgets = new();
    private InputState _input = new();
    private int _steeringMaxAngle = 900;

    public List<HudWidget> Widgets
    {
        get => _widgets;
        set { _widgets = value ?? new List<HudWidget>(); Invalidate(); }
    }

    public InputState InputState
    {
        get => _input;
        set { _input = value ?? new InputState(); Invalidate(); }
    }

    public int SteeringMaxAngle
    {
        get => _steeringMaxAngle;
        set { _steeringMaxAngle = value; Invalidate(); }
    }

    public HudPreviewControl()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
        BackColor = Color.FromArgb(21, 27, 46); // Dark navy card

        _widgets = HudLayoutManager.LoadLayout(null);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;

        int areaW = Width;
        int areaH = Height;

        if (areaW <= 10 || areaH <= 10) return;

        // Draw card border
        using (var borderPen = new Pen(Color.FromArgb(30, 38, 64), 1.5f))
        {
            var cardRect = new Rectangle(0, 0, areaW - 1, areaH - 1);
            using var path = GetRoundedRectPath(cardRect, 12);
            g.DrawPath(borderPen, path);
        }

        foreach (var w in _widgets)
        {
            int wPx = (int)(areaW * w.WFrac);
            int hPx = (int)(areaH * w.HFrac);

            if (w.IsSteering)
            {
                int m = Math.Min(wPx, hPx);
                wPx = m;
                hPx = m;
            }

            int xPx = (int)(areaW * w.Cx - wPx / 2f);
            int yPx = (int)(areaH * w.Cy - hPx / 2f);

            var rect = new Rectangle(xPx, yPx, wPx, hPx);

            if (w.IsSteering)
            {
                DrawSteeringWheel(g, rect);
            }
            else if (w.IsPedal)
            {
                DrawPedal(g, rect, w);
            }
            else
            {
                DrawButton(g, rect, w);
            }
        }
    }

    private void DrawSteeringWheel(Graphics g, Rectangle rect)
    {
        int size = Math.Min(rect.Width, rect.Height);
        if (size <= 10) return;

        float centerX = rect.X + rect.Width / 2f;
        float centerY = rect.Y + rect.Height / 2f;
        float radius = size / 2f;

        // Steering rotation angle
        float normSteer = _input.NormalizedSteering; // -1..1
        float angleDeg = normSteer * (_steeringMaxAngle / 2f);

        var state = g.Save();
        g.TranslateTransform(centerX, centerY);
        g.RotateTransform(angleDeg);

        // 1. Outer rim grip (Dark navy metallic)
        using (var gripBrush = new SolidBrush(Color.FromArgb(30, 37, 58)))
            g.FillEllipse(gripBrush, -radius, -radius, radius * 2, radius * 2);

        using (var innerRimBrush = new SolidBrush(Color.FromArgb(15, 19, 32)))
            g.FillEllipse(innerRimBrush, -(radius * 0.72f), -(radius * 0.72f), radius * 1.44f, radius * 1.44f);

        // Top center alignment marker (Blue strip)
        using (var topMarkerBrush = new SolidBrush(Color.FromArgb(59, 130, 246)))
            g.FillRectangle(topMarkerBrush, -4f, -radius, 8f, radius * 0.28f);

        // 2. Metallic 3-Spokes
        using (var spokePen = new Pen(Color.FromArgb(80, 95, 125), radius * 0.18f))
        {
            spokePen.StartCap = LineCap.Round;
            spokePen.EndCap = LineCap.Round;

            // Left spoke
            g.DrawLine(spokePen, 0, 0, -radius * 0.7f, radius * 0.2f);
            // Right spoke
            g.DrawLine(spokePen, 0, 0, radius * 0.7f, radius * 0.2f);
            // Bottom spoke
            g.DrawLine(spokePen, 0, 0, 0, radius * 0.7f);
        }

        // 3. Center Hub & Logo Badge
        float hubRadius = radius * 0.35f;
        using (var hubBrush = new SolidBrush(Color.FromArgb(22, 28, 46)))
            g.FillEllipse(hubBrush, -hubRadius, -hubRadius, hubRadius * 2, hubRadius * 2);

        using (var hubBorder = new Pen(Color.FromArgb(99, 102, 241), 2f))
            g.DrawEllipse(hubBorder, -hubRadius, -hubRadius, hubRadius * 2, hubRadius * 2);

        // OW Logo badge
        using (var font = new Font("Segoe UI", hubRadius * 0.5f, FontStyle.Bold))
        using (var textBrush = new SolidBrush(Color.FromArgb(224, 231, 255)))
        {
            var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString("OW", font, textBrush, 0, 0, sf);
        }

        g.Restore(state);
    }

    private void DrawPedal(Graphics g, Rectangle rect, HudWidget w)
    {
        if (rect.Width <= 4 || rect.Height <= 10) return;

        // Determine value (0..1)
        float fillVal = w.Id.ToLower() switch
        {
            "gas" => _input.NormalizedThrottle,
            "brake" => _input.NormalizedBrake,
            "clutch" => _input.NormalizedClutch,
            _ => 0f
        };
        fillVal = Math.Clamp(fillVal, 0f, 1f);

        Color pedalColor = w.Id.ToLower() switch
        {
            "gas" => Color.FromArgb(34, 197, 94),     // Green
            "brake" => Color.FromArgb(239, 68, 68),   // Red
            "clutch" => Color.FromArgb(245, 158, 11),  // Amber
            _ => Color.FromArgb(99, 102, 241)
        };

        // Outer pedal track background
        using var trackPath = GetRoundedRectPath(rect, 10);
        using (var bgBrush = new SolidBrush(Color.FromArgb(18, 24, 40)))
            g.FillPath(bgBrush, trackPath);

        using (var borderPen = new Pen(Color.FromArgb(35, 45, 70), 1f))
            g.DrawPath(borderPen, trackPath);

        // Fill bar from bottom
        if (fillVal > 0.01f)
        {
            int fillH = (int)(rect.Height * fillVal);
            var fillRect = new Rectangle(rect.X, rect.Bottom - fillH, rect.Width, fillH);
            using var fillPath = GetRoundedRectPath(fillRect, 10);

            using var fillBrush = new LinearGradientBrush(
                fillRect,
                Color.FromArgb(80, pedalColor),
                pedalColor,
                LinearGradientMode.Vertical
            );
            g.FillPath(fillBrush, fillPath);
        }

        // Draw track lines
        int lineSpacing = 12;
        int lineCount = rect.Height / lineSpacing;
        using var linePen = new Pen(Color.FromArgb(15, 255, 255, 255), 1f);
        for (int i = 1; i < lineCount; i++)
        {
            int y = rect.Bottom - i * lineSpacing;
            g.DrawLine(linePen, rect.X + 4, y, rect.Right - 4, y);
        }

        // Pedal label above/inside
        using var fontLbl = new Font("Segoe UI", 7.5f, FontStyle.Bold);
        using var fontVal = new Font("Segoe UI", 9f, FontStyle.Bold);
        using var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };

        // Label at top
        using (var lblBrush = new SolidBrush(Color.FromArgb(148, 163, 184)))
            g.DrawString(w.Label.ToUpper(), fontLbl, lblBrush, rect.X + rect.Width / 2f, rect.Y + 10, sf);

        // Value in center
        int percent = (int)(fillVal * 100);
        using (var valBrush = new SolidBrush(fillVal > 0.01f ? Color.White : Color.FromArgb(100, 116, 139)))
            g.DrawString(percent.ToString(), fontVal, valBrush, rect.X + rect.Width / 2f, rect.Y + rect.Height / 2f + 5, sf);
    }

    private void DrawButton(Graphics g, Rectangle rect, HudWidget w)
    {
        if (rect.Width <= 4 || rect.Height <= 4) return;

        bool isPressed = false;
        if (w.VJoyBtn >= 1 && w.VJoyBtn <= 24)
        {
            isPressed = _input.ButtonStates[w.VJoyBtn - 1];
        }

        using var path = GetRoundedRectPath(rect, 8);

        if (isPressed)
        {
            // Glowing active state
            using var bgBrush = new SolidBrush(Color.FromArgb(59, 130, 246)); // Bright electric blue
            g.FillPath(bgBrush, path);

            using var borderPen = new Pen(Color.FromArgb(147, 197, 253), 2f);
            g.DrawPath(borderPen, path);

            using var font = new Font("Segoe UI", Math.Max(7f, rect.Height * 0.35f), FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.White);
            using var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(w.Label, font, textBrush, rect.X + rect.Width / 2f, rect.Y + rect.Height / 2f, sf);
        }
        else
        {
            // Normal state
            using var bgBrush = new SolidBrush(Color.FromArgb(32, 42, 68)); // Translucent navy button
            g.FillPath(bgBrush, path);

            using var borderPen = new Pen(Color.FromArgb(48, 60, 92), 1f);
            g.DrawPath(borderPen, path);

            using var font = new Font("Segoe UI", Math.Max(7f, rect.Height * 0.35f), FontStyle.Bold);
            using var textBrush = new SolidBrush(Color.FromArgb(148, 163, 184));
            using var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
            g.DrawString(w.Label, font, textBrush, rect.X + rect.Width / 2f, rect.Y + rect.Height / 2f, sf);
        }
    }

    private static GraphicsPath GetRoundedRectPath(Rectangle r, int radius)
    {
        var path = new GraphicsPath();
        if (r.Width <= 1 || r.Height <= 1)
        {
            path.AddRectangle(r);
            return path;
        }

        int diameter = radius * 2;
        if (diameter > r.Width) diameter = r.Width;
        if (diameter > r.Height) diameter = r.Height;

        path.AddArc(r.X, r.Y, diameter, diameter, 180, 90);
        path.AddArc(r.Right - diameter, r.Y, diameter, diameter, 270, 90);
        path.AddArc(r.Right - diameter, r.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(r.X, r.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }
}
