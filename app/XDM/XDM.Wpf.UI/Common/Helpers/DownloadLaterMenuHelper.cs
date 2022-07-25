using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using XDM.Core.UI;
using XDMApp;

namespace XDM.Wpf.UI.Common.Helpers
{
    internal class DownloadLaterMenuHelper
    {
        internal static void PopulateMenuAndAttachEvents(
            EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked,
            Button button,
            FrameworkElement window)
        {
            var nctx = (ContextMenu)window.FindResource("DownloadLaterContextMenu");
            nctx.Items.Clear();
            foreach (var queue in QueueManager.Queues)
            {
                var menuItem = new MenuItem
                {
                    Tag = queue.ID,
                    Header = queue.Name
                };
                menuItem.Click += (s, e) =>
                {
                    MenuItem m = (MenuItem)e.OriginalSource;
                    var args = new DownloadLaterEventArgs((string)m.Tag);
                    DownloadLaterClicked?.Invoke(window, args);
                };
                nctx.Items.Add(menuItem);
            }
            nctx.Items.Add(window.FindResource("DontAddToQueueMenuItem"));
            nctx.Items.Add(window.FindResource("QueueAndSchedulerMenuItem"));

            nctx.PlacementTarget = button;
            nctx.Placement = PlacementMode.Bottom;
            nctx.IsOpen = true;
        }
    }
}
