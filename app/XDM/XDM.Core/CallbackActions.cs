using System;
using System.IO;
using TraceLog;
using XDM.Core;
using XDM.Core.UI;

namespace XDM.Core
{
    internal static class CallbackActions
    {
        public static void DownloadStarted(string id, IMainView peer)
        {
            var download = peer.FindInProgressItem(id);
            if (download == null) return;
            download.Status = DownloadStatus.Downloading;
        }

        public static void DownloadFailed(string id, IMainView peer)
        {
            var download = peer.FindInProgressItem(id);
            if (download == null) return;
            download.Status = DownloadStatus.Stopped;
        }

        public static void DownloadFinished(string id, long finalFileSize, string filePath,
            IMainView peer, IAppService app, Action callback)
        {
            Log.Debug("Final file name: " + filePath);
            var download = peer.FindInProgressItem(id);
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

            peer.AddToTop(finishedEntry);
            peer.Delete(download);

            QueueManager.RemoveFinishedDownload(download.DownloadEntry.Id);

            if (app.ActiveDownloadCount == 0 && peer.IsInProgressViewSelected)
            {
                Log.Debug("switching to finished listview");
                peer.SwitchToFinishedView();
            }

            callback.Invoke();
        }
    }
}
