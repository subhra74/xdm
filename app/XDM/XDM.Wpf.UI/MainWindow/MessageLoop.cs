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

        public void Start()
        {
            ComponentDispatcher.ThreadFilterMessage += ComponentDispatcher_ThreadFilterMessage;
        }

        internal void ComponentDispatcher_ThreadFilterMessage(ref MSG msg, ref bool handled)
        {
            switch (msg.message)
            {
                case 0x16: //WM_ENDSESSION
                    {
                        AppTrayIcon.DetachFromSystemTray();
                        Log.Debug("WM_ENDSESSION message received, exiting application...");
                        Environment.Exit(0);
                        break;
                    }
                case 0x0002: // WM_DESTROY
                    {
                        clipboarMonitor.ChangeClipboardChain();
                        handled = true;
                        break;
                    }
                case 0x030D: // WM_CHANGECBCHAIN
                    {
                        clipboarMonitor.OnChangeCBChain(ref msg);
                        handled = true;
                        break;
                    }
                case 0x0308: // WM_DRAWCLIPBOARD
                    {
                        clipboarMonitor.OnDrawClipboard(ref msg);
                        handled = true;
                        break;
                    }
                default:
                    handled = false;
                    break;
            }
        }
    }
}
