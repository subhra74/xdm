//using System;
//using System.Collections.Generic;
//using System.Linq;
//using System.Windows.Forms;
//using XDM.Core.Lib.Common;
//using XDM.Core.Lib.UI;
//using XDMApp;

//namespace XDM.WinForm.UI
//{
//    internal static class QueueWindowManager
//    {
//        private static IQueuesWindow? queueWindow;
//        private static Action<List<DownloadQueue>>? onQueueModified;
//        private static Action<IEnumerable<string>>? onQueueStart;
//        private static Action<IEnumerable<string>>? onQueueStop;

//        internal static void RefreshView()
//        {
//            if (queueWindow != null)
//            {
//                queueWindow.RefreshView();
//            }
//        }

//        internal static void ShowWindow(
//            IWin32Window window,
//            Action<List<DownloadQueue>>? queueModified,
//            Action<IEnumerable<string>>? queueStart,
//            Action<IEnumerable<string>>? queueStop,
//            QueuesWindow qwin
//            )
//        {
//            if (queueWindow != null)
//            {
//                queueWindow.ShowWindow(window);
//                return;
//            }

//            var queuesCopy = QueueManager.Queues.Select(q => new DownloadQueue(q.ID, q.Name)
//            {
//                DownloadIds = q.DownloadIds.Select(d => d).ToList(),
//                Schedule = q.Schedule
//            });

//            onQueueModified = queueModified;
//            onQueueStart = queueStart;
//            onQueueStop = queueStop;

//            queueWindow = qwin;
//            queueWindow.SetData(queuesCopy);
//            queueWindow.QueuesModified += QueueWindow_QueuesModified;
//            queueWindow.QueueStartRequested += QueueWindow_QueueStartRequested;
//            queueWindow.QueueStopRequested += QueueWindow_QueueStopRequested;
//            queueWindow.WindowClosing += QueueWindow_WindowClosing;
//            queueWindow.ShowWindow(window);
//        }

//        private static void QueueWindow_WindowClosing(object sender, EventArgs e)
//        {
//            queueWindow = null;
//            onQueueModified = null;
//            onQueueStop = null;
//            onQueueStart = null;
//        }

//        private static void QueueWindow_QueueStopRequested(object sender, DownloadListEventArgs e)
//        {
//            onQueueStop?.Invoke(e.Downloads);
//        }

//        private static void QueueWindow_QueueStartRequested(object sender, DownloadListEventArgs e)
//        {
//            onQueueStart?.Invoke(e.Downloads);
//        }

//        private static void QueueWindow_QueuesModified(object sender, QueueListEventArgs e)
//        {
//            onQueueModified?.Invoke(e.Queues);
//        }
//    }
//}
