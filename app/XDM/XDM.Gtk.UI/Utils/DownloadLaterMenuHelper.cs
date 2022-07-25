using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core.UI;
using XDM.Core;

namespace XDM.GtkUI.Utils
{
    internal static class DownloadLaterMenuHelper
    {
        internal static void PopulateMenuAndAttachEvents(
            Action<DownloadLaterEventArgs>? DownloadLaterClicked,
            Menu nctx,
            MenuItem dontAddToQueueMenuItem,
            MenuItem queueAndSchedulerMenuItem,
            Window window)
        {
            foreach (var queue in QueueManager.Queues)
            {
                var menuItem = new MenuItem
                {
                    Name = queue.ID,
                    Label = queue.Name
                };
                menuItem.Activated += (s, e) =>
                {
                    var args = new DownloadLaterEventArgs((string)menuItem.Name);
                    DownloadLaterClicked?.Invoke(args);
                };
                nctx.Append(menuItem);
            }
            nctx.Append(dontAddToQueueMenuItem);
            nctx.Append(queueAndSchedulerMenuItem);

            nctx.ShowAll();
        }
    }
}
