using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.UI
{
    public class QueueSelectionEventArgs : EventArgs
    {
        public string SelectedQueueId { get; }
        public IEnumerable<string> DownloadIds { get; }
        public QueueSelectionEventArgs(string id, IEnumerable<string> downloadIds)
        {
            this.SelectedQueueId = id;
            this.DownloadIds = downloadIds;
        }
    }
}
