using System;
using System.Runtime.InteropServices;
using TraceLog;
using XDM.Core;

namespace XDM.Wpf.UI
{
    public class Win32ClipboarMonitor : IPlatformClipboardMonitor
    {
        private IntPtr hWndNextWindow, hWndCurrentWindow;
        public event EventHandler? ClipboardChanged;

        public Win32ClipboarMonitor(IntPtr hWndCurrentWindow)
        {
            this.hWndCurrentWindow = hWndCurrentWindow;
        }

        public void ChangeClipboardChain()
        {
            ChangeClipboardChain(hWndCurrentWindow, hWndNextWindow);
        }

        public void OnChangeCBChain(int message, IntPtr wParam, IntPtr lParam)
        {
            if (wParam == hWndNextWindow)
                hWndNextWindow = lParam;
            else if (hWndNextWindow != IntPtr.Zero)
                SendMessage(hWndNextWindow, message, wParam, lParam);
        }

        public void OnDrawClipboard(int message, IntPtr wParam, IntPtr lParam)
        {
            OnClipboardChanged();
            SendMessage(hWndNextWindow, message, wParam, lParam);
        }

        public void StartClipboardMonitoring()
        {
            Log.Debug("Starting clipboard monitoring");
            hWndNextWindow = SetClipboardViewer(this.hWndCurrentWindow);
        }

        public void StopClipboardMonitoring()
        {
            Log.Debug("Stopping clipboard monitoring");
            ChangeClipboardChain(this.hWndCurrentWindow, hWndNextWindow);
        }

        public string GetClipboardText()
        {
            try
            {
                var text = System.Windows.Clipboard.GetText();
                if (!string.IsNullOrEmpty(text))
                {
                    return text;
                }
            }
            catch { }
            return null;
        }

        private void OnClipboardChanged()
        {
            Log.Debug("Clipboard changed");
            this.ClipboardChanged?.Invoke(this, EventArgs.Empty);
        }

        [DllImport("User32.dll", CharSet = CharSet.Auto)]
        public static extern IntPtr SetClipboardViewer(IntPtr hWndNewViewer);

        [DllImport("User32.dll", CharSet = CharSet.Auto)]
        public static extern bool ChangeClipboardChain(IntPtr hWndRemove, IntPtr hWndNewNext);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        public static extern int SendMessage(IntPtr hwnd, int wMsg, IntPtr wParam, IntPtr lParam);

    }
}
