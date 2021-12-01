using System;
using System.Drawing;
using System.Windows.Forms;
using XDM.Core.Lib.UI;
using XDMApp;

namespace XDM.WinForm.UI
{
    internal static class DownloadLaterMenuHelper
    {
        internal static void PopulateMenuAndAttachEvents(
            ContextMenuStrip popupMenu,
            EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked,
            ToolStripMenuItem doNotAddToQueueToolStripMenuItem,
            ToolStripMenuItem manageQueueAndSchedulersToolStripMenuItem,
            Button button2,
            IWin32Window window)
        {
            popupMenu.Items.Clear();
            foreach (var queue in QueueManager.Queues)
            {
                var menuItem = new ToolStripMenuItem
                {
                    Name = queue.ID,
                    Text = queue.Name
                };
                menuItem.Click += (s, _) =>
                {
                    ToolStripMenuItem m = (ToolStripMenuItem)s;
                    var args = new DownloadLaterEventArgs(m.Name);
                    DownloadLaterClicked?.Invoke(window, args);
                };
                popupMenu.Items.Add(menuItem);
            }
            popupMenu.Items.Add(new ToolStripSeparator());
            popupMenu.Items.Add(doNotAddToQueueToolStripMenuItem);
            popupMenu.Items.Add(manageQueueAndSchedulersToolStripMenuItem);
            popupMenu.Show(button2, new Point(0, button2.Height));
        }
    }
}
