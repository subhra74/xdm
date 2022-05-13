using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using TraceLog;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using XDM.Core.Lib.Util;
using YDLWrapper;

namespace XDM.Common.UI
{
    public class VideoDownloaderController
    {
        private YDLProcess? ydl;
        private List<YDLVideoEntry> videoItemList;
        private List<int> videoQualities;
        private IVideoDownloadView view;

        public VideoDownloaderController(IVideoDownloadView view, IAppUI appUI, IApp app)
        {
            this.view = view;
            view.SearchClicked += (_, _) =>
            {
                var url = view.Url;
                if (Helpers.IsUriValid(url))
                {
                    view.SwitchToProcessingPage();
                    ProcessVideo(url, result => appUI.RunOnUiThread(() =>
                    {
                        if (result != null)
                        {
                            view.SwitchToFinalPage();
                            SetVideoResultList(result);
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
        }

        public void Run()
        {
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

        private void ProcessVideo(string url, Action<List<YDLVideoEntry>?> callback)
        {
            ydl = new YDLProcess
            {
                Uri = new Uri(url)
            };
            new System.Threading.Thread(() =>
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
    }
}
