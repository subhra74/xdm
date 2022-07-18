using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.UI
{
    public class DownloadListEventArgs : EventArgs
    {
        public IEnumerable<string> Downloads { get; }
        public DownloadListEventArgs(IEnumerable<string> downloads)
        {
            this.Downloads = downloads;
        }
    }
}
