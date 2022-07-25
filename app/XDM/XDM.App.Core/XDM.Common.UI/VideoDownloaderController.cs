using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using TraceLog;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.UI;
using XDM.Core.Util;
using YDLWrapper;

namespace XDM.Common.UI
{
    public class VideoDownloaderController
    {
        private YDLProcess? ydl;
        private List<YDLVideoEntry> videoItemList;
        private List<int> videoQualities;
        private IVideoDownloadView view;
        private IAppUIController appUI;
        private IAppService app;

        public VideoDownloaderController(IVideoDownloadView view, IAppUIController appUI, IAppService app)
        {
            this.appUI = appUI;
            this.app = app;
            this.view = view;

            var browsers = new Dictionary<string, string>
            {
                ["Google Chrome"] = "chrome",
                ["Microsoft Edge"] = "edge",
                ["Mozilla Firefox"] = "firefox",
                ["Brave"] = "brave",
                ["Opera"] = "opera",
                ["Chromium"] = "chromium",
                ["Safari"] = "safari",
                ["Vivaldi"] = "vivaldi"
            };

            this.view.AllowedBrowsers = browsers.Keys.ToList();

            view.SearchClicked += (_, _) =>
            {
                var url = view.Url;
                string? browser = null;
                if (!string.IsNullOrEmpty(view.SelectedBrowser))
                {
                    browsers.TryGetValue(view.SelectedBrowser!, out browser);
                }
                if (Helpers.IsUriValid(url))
                {
                    view.SwitchToProcessingPage();
                    ProcessVideo(url, browser, result => appUI.RunOnUiThread(() =>
                    {
                        if (result != null)
                        {
                            view.SwitchToFinalPage();
                            SetVideoResultList(result);
                        }
                        else
                        {
                            view.SwitchToErrorPage();
                        }
                    }));
                }
                else
                {
                    appUI.ShowMessageBox(view, TextResource.GetText("MSG_INVALID_URL"));
                }
            };

            view.CancelClicked += (_, _) =>
            {
                CancelOperation();
                view.SwitchToInitialPage();
            };

            view.WindowClosed += (_, _) =>
            {
                CancelOperation();
            };

            view.BrowseClicked += (_, _) =>
            {
                var folder = view.SelectFolder();
                if (!string.IsNullOrEmpty(folder))
                {
                    view.DownloadLocation = folder;
                    Config.Instance.UserSelectedDownloadFolder = folder;
                    Helpers.UpdateRecentFolderList(folder);
                }
            };

            view.DownloadClicked += View_DownloadClicked;
            view.DownloadLaterClicked += View_DownloadLaterClicked;
            view.QueueSchedulerClicked += (s, e) =>
            {
                appUI.ShowQueueWindow(s);
            };
        }

        private void View_DownloadLaterClicked(object? sender, DownloadLaterEventArgs e)
        {
            DownloadSelectedItems(false, e.QueueId);
        }

        private void View_DownloadClicked(object? sender, EventArgs e)
        {
            DownloadSelectedItems(true, null);
        }

        public void Run()
        {
            var url = appUI.GetUrlFromClipboard();
            if (url != null && Helpers.IsUriValid(url))
            {
                view.Url = url;
            }
            view.DownloadLocation = Helpers.GetVideoDownloadFolder();
            view.ShowWindow();
        }

        private void SetVideoResultList(List<YDLVideoEntry> items)
        {
            if (items == null) return;

            this.videoItemList = items;

            var formatSet = new HashSet<int>();
            foreach (var item in items)
            {
                if (item.Formats != null)
                {
                    item.Formats.ForEach(item =>
                    {
                        if (!string.IsNullOrEmpty(item.Height))
                        {
                            if (Int32.TryParse(item.Height, out int height))
                            {
                                formatSet.Add(height);
                            }
                        }
                    });
                }
            }
            var formatsList = new List<int>(formatSet);
            formatsList.Sort();
            formatsList.Reverse();
            this.videoQualities = formatsList;

            var videoList = this.videoItemList.Select(x => x.Title);
            var formatList = this.videoQualities.Select(n => $"{n}p");

            view.SetVideoResultList(videoList, formatList);

            if (formatsList.Count > 0)
            {
                view.SelectedFormat = 0;
            }
        }

        private void CancelOperation()
        {
            try
            {
                if (ydl != null)
                {
                    ydl.Cancel();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error cancelling ydl");
            }
        }

        private void ProcessVideo(string url, string? browser, Action<List<YDLVideoEntry>?> callback)
        {
            ydl = new YDLProcess
            {
                Uri = new Uri(url),
                BrowserName = browser
            };
            new Thread(() =>
            {
                try
                {
                    ydl.Start();
                    callback.Invoke(YDLOutputParser.Parse(ydl.JsonOutputFile));
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error while running youtube-dl");
                    callback.Invoke(null);
                }
            }).Start();
        }

        private void DownloadSelectedItems(bool startImmediately, string? queueId)
        {
            if (string.IsNullOrEmpty(view.DownloadLocation))
            {
                appUI!.ShowMessageBox(view, TextResource.GetText("MSG_CAT_FOLDER_MISSING"));
                return;
            }
            if (this.view.SelectedItemCount == 0)
            {
                appUI!.ShowMessageBox(view, TextResource.GetText("BAT_SELECT_ITEMS"));
                return;
            }
            var quality = -1;
            if (view.SelectedFormat >= 0)
            {
                quality = this.videoQualities[view.SelectedFormat];
            }

            var selectedIndices = view.SelectedRows;
            foreach (var index in selectedIndices)
            {
                var entry = videoItemList[index];
                var fmt = FindMatchingFormatByQuality(entry, quality);
                if (fmt.HasValue)
                {
                    AddDownload(fmt.Value, startImmediately, queueId);
                }
            }
            view.CloseWindow();
        }

        private YDLVideoFormatEntry? FindMatchingFormatByQuality(YDLVideoEntry videoEntry, int quality = -1)
        {
            if (videoEntry.Formats.Count == 0) return null;
            if (quality == -1)
            {
                return videoEntry.Formats[0];
            }
            //if we find an mp4 video with desired height/resolution return it
            var fmt = FindOnlyMatchingMp4(videoEntry, quality);
            if (fmt != null)
            {
                return fmt;
            }
            //if no mp4 is found look for other formats like mkv or webm
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height == quality)
                {
                    return format;
                }
            }
            //so far no luck, try to find next best resoultion
            var max = -1;
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height > 0 &&
                    quality > height)
                {
                    if (height > max)
                    {
                        max = height;
                        fmt = format;
                    }
                }
            }
            if (fmt != null)
            {
                return fmt;
            }
            //could not found anything as per criteria, return the first format
            return videoEntry.Formats[0];
        }

        private YDLVideoFormatEntry? FindOnlyMatchingMp4(YDLVideoEntry videoEntry, int quality)
        {
            if (videoEntry.Formats.Count == 0) return null;
            foreach (var format in videoEntry.Formats)
            {
                if (!string.IsNullOrEmpty(format.Height) &&
                    Int32.TryParse(format.Height, out int height) &&
                    height == quality &&
                    (format.FileExt?.ToLowerInvariant()?.EndsWith("mp4") ?? false))
                {
                    return format;
                }
            }
            return null;
        }

        private void AddDownload(YDLVideoFormatEntry videoEntry, bool startImmediately, string? queueId)
        {
            object? info = videoEntry.YDLEntryType switch
            {
                YDLEntryType.Http => new SingleSourceHTTPDownloadInfo
                {
                    Uri = videoEntry.VideoUrl
                },
                YDLEntryType.Dash => new DualSourceHTTPDownloadInfo
                {
                    Uri1 = videoEntry.VideoUrl,
                    Uri2 = videoEntry.AudioUrl
                },
                YDLEntryType.Hls => new MultiSourceHLSDownloadInfo
                {
                    VideoUri = videoEntry.VideoUrl,
                    AudioUri = videoEntry.AudioUrl
                },
                YDLEntryType.MpegDash => new MultiSourceDASHDownloadInfo
                {
                    VideoSegments = videoEntry.VideoFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                    AudioSegments = videoEntry.AudioFragments?.Select(x => new Uri(new Uri(videoEntry.FragmentBaseUrl), x.Path)).ToList(),
                    AudioFormat = videoEntry.AudioFormat != null ? "." + videoEntry.AudioFormat : null,
                    VideoFormat = videoEntry.VideoFormat != null ? "." + videoEntry.VideoFormat : null,
                    Url = videoEntry.VideoUrl
                },
            };
            if (info != null)
            {
                app!.SubmitDownload(
                        info,
                        videoEntry.Title + "." + videoEntry.FileExt,
                        FileNameFetchMode.None,
                        view.DownloadLocation,
                        startImmediately,
                        view.Authentication, view.Proxy ?? Config.Instance.Proxy,
                        view.EnableSpeedLimit,
                        view.EnableSpeedLimit ? view.SpeedLimit : 0, queueId,
                        false
                    );
            }
        }
    }
}
