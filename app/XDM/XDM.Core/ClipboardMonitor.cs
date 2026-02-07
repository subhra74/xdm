using System;
using System.Collections.Generic;
using System.Text;
using TraceLog;
using XDM.Core.Util;

namespace XDM.Core
{
    public interface IClipboardMonitor
    {
        void Start();
        void Stop();
    }

    public class ClipboardMonitor : IClipboardMonitor
    {
        private bool isClipboardMonitorActive = false;
        private string lastClipboardText;

        public ClipboardMonitor()
        {
            ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;
        }

        private void ApplicationContext_ApplicationEvent(object sender, ApplicationEvent e)
        {
            if (e.EventType == "ConfigChanged")
            {
                if (Config.Instance.MonitorClipboard)
                {
                    Start();
                }
                else
                {
                    Stop();
                }
            }
        }

        public void Start()
        {
            Log.Debug("StartClipboardMonitor");
            if (isClipboardMonitorActive) return;
            var cm = ApplicationContext.Application.GetPlatformClipboardMonitor();
            if (Config.Instance.MonitorClipboard)
            {
                cm.StartClipboardMonitoring();
                isClipboardMonitorActive = true;
                cm.ClipboardChanged += Cm_ClipboardChanged;
            }
        }

        public void Stop()
        {
            if (!isClipboardMonitorActive) return;
            var cm = ApplicationContext.Application.GetPlatformClipboardMonitor();
            cm.StopClipboardMonitoring();
            isClipboardMonitorActive = false;
            cm.ClipboardChanged -= Cm_ClipboardChanged;
        }

        private void Cm_ClipboardChanged(object? sender, EventArgs e)
        {
            var text = ApplicationContext.Application.GetPlatformClipboardMonitor().GetClipboardText();
            if (!string.IsNullOrEmpty(text) && Helpers.IsUriValid(text) && text != lastClipboardText)
            {
                lastClipboardText = text;
                ApplicationContext.CoreService.AddDownload(new Message { Url = text });
            }
        }
    }
}
