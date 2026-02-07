using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows.Interop;
using TraceLog;

namespace XDM.Wpf.UI
{
    internal class MessageLoop
    {
        private Win32ClipboarMonitor clipboarMonitor;

        internal MessageLoop(Win32ClipboarMonitor clipboarMonitor)
        {
            this.clipboarMonitor = clipboarMonitor;
        }

        public void Start(IntPtr handle)
        {
            HwndSource source = HwndSource.FromHwnd(handle);
            source.AddHook(new HwndSourceHook(WndProc));
        }

        private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            //  do stuff
            switch (msg)
            {
                case 0x0002: // WM_DESTROY
                    {
                        clipboarMonitor.ChangeClipboardChain();
                        handled = true;
                        break;
                    }
                case 0x030D: // WM_CHANGECBCHAIN
                    {
                        clipboarMonitor.OnChangeCBChain(msg, wParam, lParam);
                        handled = true;
                        break;
                    }
                case 0x0308: // WM_DRAWCLIPBOARD
                    {
                        clipboarMonitor.OnDrawClipboard(msg, wParam, lParam);
                        handled = true;
                        break;
                    }
                default:
                    handled = false;
                    break;
            }
            return IntPtr.Zero;
        }
    }
}
