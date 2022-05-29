using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.Downloader.Progressive.DualHttp;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Util;

namespace XDM.Common.UI
{
    public static class LinkRefreshDialogHelper
    {
        public static bool RefreshLink(BaseDownloadEntry item, IApp app, IRefreshLinkDialogSkeleton dialog)
        {
            try
            {

                if (item.DownloadType != "Http" && item.DownloadType != "Dash")
                {
                    return false;
                }
                string referer = null;
                if (item.DownloadType == "Http")
                {
                    var state = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(
                        File.ReadAllBytes(Path.Combine(Config.DataDir, item.Id + ".state")));
                    //JsonConvert.DeserializeObject<SingleSourceHTTPDownloaderState>(
                    //    File.ReadAllText(Path.Combine(Config.DataDir, item.Id + ".state")));
                    referer = GetReferer(state.Headers);
                }
                else if (item.DownloadType == "Dash")
                {
                    var state = DownloadStateStore.DualSourceHTTPDownloaderStateFromBytes(
                        File.ReadAllBytes(Path.Combine(Config.DataDir, item.Id + ".state")));
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
                        app.ClearRefreshLinkCandidate();
                    };

                    OpenBrowser(referer);
                    if (item.DownloadType == "Http")
                    {
                        var downloader = new SingleSourceHTTPDownloader(item.Id);
                        downloader.RestoreState();
                        app.RefreshedLinkReceived += (_, _) => dialog.LinkReceived();
                        app.WaitFromRefreshedLink(downloader);
                    }
                    else if (item.DownloadType == "Dash")
                    {
                        var downloader = new DualSourceHTTPDownloader(item.Id);
                        downloader.RestoreState();
                        app.RefreshedLinkReceived += (_, _) => dialog.LinkReceived();
                        app.WaitFromRefreshedLink(downloader);
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
            Helpers.OpenBrowser(url);
        }
    }
}
