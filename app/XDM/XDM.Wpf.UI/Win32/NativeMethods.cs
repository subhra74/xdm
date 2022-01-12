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

        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

        private const int GWL_STYLE = -16;
        private const int WS_MAXIMIZEBOX = 0x10000;
        private const int WS_MINIMIZEBOX = 0x20000;

        public static void DisableMaxButton(Window window)
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            var value = GetWindowLong(hwnd, GWL_STYLE);
            SetWindowLong(hwnd, GWL_STYLE, (int)(value & ~WS_MAXIMIZEBOX));
        }
        public static void DisableMinMaxButton(Window window)
        {
            var hwnd = new WindowInteropHelper(window).Handle;
            var value = GetWindowLong(hwnd, GWL_STYLE);
            SetWindowLong(hwnd, GWL_STYLE, (int)(value & ~WS_MAXIMIZEBOX & ~WS_MINIMIZEBOX));
        }
    }
}
