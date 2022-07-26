using System;
using System.IO;
using TraceLog;
using XDM.Core;
using XDM.Core.UI;

namespace XDM.Core
{
    internal static class CallbackActions
    {
        public static void DownloadStarted(string id)
        {
            var download = AppInstance.MainWindow.FindInProgressItem(id);
            if (download == null) return;
            download.Status = DownloadStatus.Downloading;
        }

        public static void DownloadFailed(string id)
        {
            var download = AppInstance.MainWindow.FindInProgressItem(id);
            if (download == null) return;
            download.Status = DownloadStatus.Stopped;
        }

        public static void DownloadFinished(string id, long finalFileSize, string filePath, Action callback)
        {
            Log.Debug("Final file name: " + filePath);
            var download = AppInstance.MainWindow.FindInProgressItem(id);
            if (download == null) return;
            var downloadEntry = download.DownloadEntry;
            downloadEntry.Progress = 100;

            var finishedEntry = new FinishedDownloadEntry
            {
                Name = Path.GetFileName(filePath),
                Id = downloadEntry.Id,
                DateAdded = downloadEntry.DateAdded,
                Size = downloadEntry.Size > 0 ? downloadEntry.Size : finalFileSize,
                DownloadType = downloadEntry.DownloadType,
                TargetDir = Path.GetDirectoryName(filePath)!,
                PrimaryUrl = downloadEntry.PrimaryUrl,
                Authentication = downloadEntry.Authentication,
                Proxy = downloadEntry.Proxy
            };

            AppInstance.MainWindow.AddToTop(finishedEntry);
            AppInstance.MainWindow.Delete(download);

            QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

            if (AppInstance.Core.ActiveDownloadCount == 0 && AppInstance.MainWindow.IsInProgressViewSelected)
            {
                Log.Debug("switching to finished listview");
                AppInstance.MainWindow.SwitchToFinishedView();
            }

            callback.Invoke();
        }
    }
}
