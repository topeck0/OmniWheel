using System;
using System.Threading;
using System.Windows.Forms;
using OmniWheelPC.UI;

namespace OmniWheelPC;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();

        // Single-instance protection using named Mutex
        using var mutex = new Mutex(true, "OmniWheelPC_SingleInstance", out bool owned);
        if (!owned)
        {
            MessageBox.Show(
                "OmniWheel PC is already running.\n\nClose the existing instance first.",
                "OmniWheel PC",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        Application.Run(new MainForm());
    }
}
