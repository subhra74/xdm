using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.Common
{
    public interface IClipboardMonitor
    {
        void StartClipboardMonitoring();

        void StopClipboardMonitoring();

        event EventHandler? ClipboardChanged;
        string GetClipboardText();
    }
}
