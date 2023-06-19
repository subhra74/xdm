using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.IO;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public static class LinkRefreshDialogUIController
    {
        public static bool RefreshLink(DownloadItemBase item, IRefreshLinkDialog dialog)
        {
            try
            {
                if (item.DownloadType != "Http" && item.DownloadType != "Dash")
                {
                    return false;
                }
                string? referer = null;
                if (item.DownloadType == "Http")
                {
                    var state = DownloadStateIO.LoadSingleSourceHTTPDownloaderState(item.Id);
                    referer = GetReferer(state.Headers);
                }
                else if (item.DownloadType == "Dash")
                {
                    var state = DownloadStateIO.LoadDualSourceHTTPDownloaderState(item.Id);
                    referer = GetReferer(state.Headers1);
                }
                else
                {
                    return false;
                }
                Log.Debug("Referer: " + referer);

                dialog.WatchingStopped += (a, b) =>
                {
                    ApplicationContext.LinkRefresher.ClearWatchList();
                };

                if (referer != null)
                {
                    OpenBrowser(referer);
                }
                if (item.DownloadType == "Http")
                {
                    var downloader = new SingleSourceHTTPDownloader(item.Id);
                    downloader.RestoreState();
                    ApplicationContext.LinkRefresher.RefreshedLinkReceived += (_, _) => dialog.LinkReceived();
                    ApplicationContext.LinkRefresher.AddToWatchList(downloader);
                }
                else if (item.DownloadType == "Dash")
                {
                    var downloader = new DualSourceHTTPDownloader(item.Id);
                    downloader.RestoreState();
                    ApplicationContext.LinkRefresher.RefreshedLinkReceived += (_, _) => dialog.LinkReceived();
                    ApplicationContext.LinkRefresher.AddToWatchList(downloader);
                }

                dialog.ShowWindow();
                return true;
            }
            catch (Exception e)
            {
                Log.Debug(e, e.Message);
            }
            return false;
        }

        private static string GetReferer(Dictionary<string, List<string>> headers)
        {
            return headers?.Where(
                header => header.Key.ToLowerInvariant() == "referer")
                .FirstOrDefault().Value?.FirstOrDefault();
        }

        private static void OpenBrowser(string url)
        {
            PlatformHelper.OpenBrowser(url);
        }
    }
}
