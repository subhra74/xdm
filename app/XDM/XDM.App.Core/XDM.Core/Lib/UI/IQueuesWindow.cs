using System;
using System.Collections.Generic;
using XDM.Core;
using XDM.Core.UI;

namespace XDM.Core.UI
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