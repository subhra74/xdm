using System;
using System.Windows.Forms;
using System.Linq;
using System.Text;
using System.Runtime.InteropServices;
using TraceLog;

namespace XDM.WinForm.UI.Win32
{
    internal static class NativeMethods
    {
        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll", SetLastError = true)]
        internal static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter,
            int x, int y, int cx, int cy, uint uFlags);

        internal static void SetWindowTopMost(Form f)
        {
            SetWindowPos(f.Handle, new IntPtr(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0040 | 0);
            Log.Debug("SetWindowPos: " + Marshal.GetLastWin32Error());
        }
    }
}
