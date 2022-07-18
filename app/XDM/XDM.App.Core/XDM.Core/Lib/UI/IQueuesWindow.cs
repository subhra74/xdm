using System;
using System.Collections.Generic;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;

namespace XDM.Core.Lib.UI
{
    public interface IQueuesWindow
    {
        event EventHandler<QueueListEventArgs>? QueuesModified;
        event EventHandler<DownloadListEventArgs>? QueueStartRequested;
        event EventHandler<DownloadListEventArgs>? QueueStopRequested;
        event EventHandler? WindowClosing;

        void RefreshView();
        void SetData(IEnumerable<DownloadQueue> queues);
        void ShowWindow(object peer);

    }
}