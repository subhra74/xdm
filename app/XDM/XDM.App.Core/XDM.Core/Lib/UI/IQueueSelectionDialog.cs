using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.UI
{
    public interface IQueueSelectionDialog : IDisposable
    {
        event EventHandler<QueueSelectionEventArgs>? QueueSelected;
        event EventHandler? ManageQueuesClicked;

        void SetData(
            IEnumerable<string> queueNames,
            IEnumerable<string> queueIds,
            IEnumerable<string> downloadIds);
        void ShowWindow(IAppWinPeer peer);
    }
}
