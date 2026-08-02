using System;
using System.Windows.Forms;
using OmniWheelPC.UI;

namespace OmniWheelPC;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
