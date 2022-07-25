using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Timers;
using System.Threading.Tasks;
using Gtk;
using XDM.Core;
using TraceLog;

namespace XDM.GtkUI
{
    public class PollingClipboardMonitor : IClipboardMonitor
    {
        private Timer timer;
        private string lastText;
        private Clipboard cb;
        public PollingClipboardMonitor()
        {
            cb = Clipboard.Get(Gdk.Selection.Clipboard);
            timer = new Timer(1000);
            timer.Elapsed += Timer_Elapsed;
        }

        private void Timer_Elapsed(object? sender, ElapsedEventArgs e)
        {
            Application.Invoke(CheckGtkClipboardContents);
        }

        private void CheckGtkClipboardContents(object? sender, EventArgs e)
        {
            if (cb == null)
            {
                Log.Debug("Clipboard is null");
                return;
            }
            var text = cb.WaitForText();
            if (text != lastText)
            {
                Log.Debug("Clipboard changed");
                lastText = text;
                this.ClipboardChanged?.Invoke(this, EventArgs.Empty);
            }
        }

        public event EventHandler? ClipboardChanged;

        public string GetClipboardText() => lastText;

        public void StartClipboardMonitoring()
        {
            timer.Start();
        }

        public void StopClipboardMonitoring()
        {
            timer.Stop();
        }
    }
}
