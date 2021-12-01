using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core.Lib.UI
{
    public interface IQueueSelectionDialog
    {
        event EventHandler<QueueSelectionEventArgs>? QueueSelected;
        event EventHandler? ManageQueuesClicked;

        void SetData(IEnumerable<string> items, string[] downloadIds);

        void ShowWindow(IAppWinPeer peer);
    }
}
