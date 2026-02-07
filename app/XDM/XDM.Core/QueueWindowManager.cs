using System;
using System.Collections.Generic;
using System.Linq;
using XDM.Core;
using XDM.Core.UI;

namespace XDM.Core
{
    internal static class QueueWindowManager
    {
        private static IQueuesWindow? queueWindow;

        internal static void RefreshView()
        {
            if (queueWindow != null)
            {
                queueWindow.RefreshView();
            }
        }

        internal static void ShowWindow(object window, IQueuesWindow qwin)
        {
            if (queueWindow != null)
            {
                return;
            }

            var queuesCopy = QueueManager.Queues.Select(q => new DownloadQueue(q.ID, q.Name)
            {
                DownloadIds = q.DownloadIds.Select(d => d).ToList(),
                Schedule = q.Schedule
            });

            queueWindow = qwin;
            queueWindow.SetData(queuesCopy);
            queueWindow.QueuesModified += QueueWindow_QueuesModified;
            queueWindow.QueueStartRequested += QueueWindow_QueueStartRequested;
            queueWindow.QueueStopRequested += QueueWindow_QueueStopRequested;
            queueWindow.WindowClosing += QueueWindow_WindowClosing;
            queueWindow.ShowWindow(window);
        }

        private static void QueueWindow_WindowClosing(object? sender, EventArgs e)
        {
            queueWindow!.QueuesModified -= QueueWindow_QueuesModified;
            queueWindow!.QueueStartRequested -= QueueWindow_QueueStartRequested;
            queueWindow!.QueueStopRequested -= QueueWindow_QueueStopRequested;
            queueWindow!.WindowClosing -= QueueWindow_WindowClosing;
            queueWindow = null;
        }

        private static void QueueWindow_QueueStopRequested(object? sender, DownloadListEventArgs e)
        {
            ApplicationContext.CoreService?.StopDownloads(e.Downloads, true);
        }

        private static void QueueWindow_QueueStartRequested(object? sender, DownloadListEventArgs e)
        {
            ApplicationContext.CoreService?.ResumeNonInteractiveDownloads(e.Downloads);
        }

        private static void QueueWindow_QueuesModified(object? sender, QueueListEventArgs e)
        {
            OnQueueModified(e.Queues);
        }

        private static void OnQueueModified(IEnumerable<DownloadQueue> queues)
        {
            var dict1 = new Dictionary<string, DownloadQueue>();
            var dict2 = new Dictionary<string, DownloadQueue>();

            foreach (var q in queues)
            {
                dict1.Add(q.ID, q);
            }

            foreach (var q in QueueManager.Queues)
            {
                dict2.Add(q.ID, q);
            }

            foreach (var queue in queues)
            {
                if (dict2.TryGetValue(queue.ID, out var q) && q != null)
                {
                    q.Name = queue.Name;
                    q.DownloadIds = queue.DownloadIds;
                    q.Schedule = queue.Schedule;

                    dict1.Remove(queue.ID);
                    dict2.Remove(q.ID);
                }
            }

            if (dict1.Count > 0)
            {
                foreach (var key in dict1.Keys)
                {
                    var queue = dict1[key];
                    QueueManager.Queues.Add(new DownloadQueue(queue.ID, queue.Name)
                    {
                        DownloadIds = queue.DownloadIds.Select(x => x).ToList(),
                        Schedule = queue.Schedule
                    });
                }
            }

            if (dict2.Count > 0)
            {
                foreach (var key in dict2.Keys)
                {
                    var queue = dict2[key];
                    QueueManager.Queues.Remove(queue);
                }
            }

            QueueManager.Save();
        }
    }
}
