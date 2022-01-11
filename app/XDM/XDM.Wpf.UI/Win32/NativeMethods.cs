using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Threading;
using XDM.Wpf.UI.Common;

namespace XDM.Wpf.UI.Win32
{
    internal static class NativeMethods
    {
        public static bool? ShowDialog(this Window window, Window owner)
        {
            EnableWindow(owner, false);
            DispatcherFrame? df = new();
            window.Show();
            window.Closed += (_, _) =>
            {
                df.Continue = false;
                df = null;
                EnableWindow(owner, true);
                owner.Activate();
            };
            Dispatcher.PushFrame(df);
            return window is IDialog dialog ? dialog.Result : null;
        }

        public static void EnableWindow(Window window, bool enable)
        {
            EnableWindow(new WindowInteropHelper(window).Handle, enable);
        }

        [DllImport("user32.dll")]
        private static extern bool EnableWindow(IntPtr hWnd, bool bEnable);
    }
}
