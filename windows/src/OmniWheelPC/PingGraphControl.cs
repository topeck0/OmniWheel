using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace OmniWheelPC.UI;

public class PingGraphControl : Control
{
    private readonly Queue<int> _pingHistory = new();
    private const int MaxSamples = 40;
    private const int MaxPingScale = 100; // ms

    public PingGraphControl()
    {
        DoubleBuffered = true;
        SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
        BackColor = Color.FromArgb(10, 13, 24);

        // Pre-fill with low ping
        for (int i = 0; i < MaxSamples; i++)
            _pingHistory.Enqueue(12 + (i % 3));
    }

    public void AddPing(int pingMs)
    {
        if (pingMs < 1) pingMs = 1;
        if (_pingHistory.Count >= MaxSamples)
            _pingHistory.Dequeue();
        _pingHistory.Enqueue(pingMs);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;

        var r = ClientRectangle;
        r.Width -= 1;
        r.Height -= 1;

        // Draw dark rounded border
        using (var borderPen = new Pen(Color.FromArgb(30, 38, 64), 1f))
            g.DrawRectangle(borderPen, r);

        var samples = _pingHistory.ToArray();
        if (samples.Length < 2) return;

        float stepX = (float)Width / (MaxSamples - 1);
        var points = new PointF[samples.Length];

        for (int i = 0; i < samples.Length; i++)
        {
            float val = Math.Min(samples[i], MaxPingScale);
            float y = Height - 3 - (val / MaxPingScale) * (Height - 6);
            points[i] = new PointF(i * stepX, y);
        }

        // Draw line segments with ping-based colors
        for (int i = 0; i < points.Length - 1; i++)
        {
            int ping = samples[i];
            Color col = ping switch
            {
                < 20 => Color.FromArgb(34, 197, 94),   // Green
                < 50 => Color.FromArgb(234, 179, 8),   // Yellow
                _ => Color.FromArgb(239, 68, 68)       // Red
            };

            using var pen = new Pen(col, 2f);
            g.DrawLine(pen, points[i], points[i + 1]);
        }
    }
}
