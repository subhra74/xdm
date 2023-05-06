using System;
using System.Diagnostics;
using System.IO;

namespace XDM.Msix.AutoLaunch
{
    static class Program
    {
        static void Main()
        {
            var psi = new ProcessStartInfo();
            psi.FileName = "xdm-app.exe";
            psi.UseShellExecute = true;
            psi.Arguments = "--background";
            Process.Start(psi);
        }
    }
}
