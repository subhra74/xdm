using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.UI
{
    public class DownloadLaterEventArgs : EventArgs
    {
        public string? QueueId { get; }
        public DownloadLaterEventArgs(string? queueId)
        {
            this.QueueId = queueId;
        }
    }
}
