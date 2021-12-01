using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.UI
{
    public class QueueSelectionEventArgs : EventArgs
    {
        public int SelectedQueueIndex { get; }
        public string[] DownloadIds { get; }
        public QueueSelectionEventArgs(int index, string[] downloadIds)
        {
            this.SelectedQueueIndex = index;
            this.DownloadIds = downloadIds;
        }
    }
}
