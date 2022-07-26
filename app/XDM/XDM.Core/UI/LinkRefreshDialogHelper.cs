using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public static class LinkRefreshDialogHelper
    {
        public static bool RefreshLink(BaseDownloadEntry item, IRefreshLinkDialogSkeleton dialog)
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
                    var state = DownloadStateStore.LoadSingleSourceHTTPDownloaderState(item.Id);
                    referer = GetReferer(state.Headers);
                    //if (!TransactedIO.ReadStream(item.Id + ".state", Config.DataDir, s =>
                    //{
                    //    var state = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(s);
                    //    referer = GetReferer(state.Headers);
                    //}))
                    //{
                    //    throw new FileNotFoundException(Path.Combine(Config.DataDir, item.Id + ".state"));
                    //}

                    //var state = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(
                    //    File.ReadAllBytes(Path.Combine(Config.DataDir, item.Id + ".state")));
                    ////JsonConvert.DeserializeObject<SingleSourceHTTPDownloaderState>(
                    ////    File.ReadAllText(Path.Combine(Config.DataDir, item.Id + ".state")));
                    //referer = GetReferer(state.Headers);
                }
                else if (item.DownloadType == "Dash")
                {
                    var state = DownloadStateStore.LoadDualSourceHTTPDownloaderState(item.Id);
                    //JsonConvert.DeserializeObject<DualSourceHTTPDownloaderState>(
                    //    File.ReadAllText(Path.Combine(Config.DataDir, item.Id + ".state")));
                    referer = GetReferer(state.Headers1);
                }
                else
                {
                    return false;
                }
                Log.Debug("Referer: " + referer);
                if (referer != null)
                {
                    dialog.WatchingStopped += (a, b) =>
                    {
                        ApplicationContext.LinkRefresher.ClearWatchList();
                    };

                    OpenBrowser(referer);
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
