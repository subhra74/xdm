using System;
using System.Linq;
using System.Collections.Generic;
using Translations;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Util;

namespace XDM.Common.UI
{
    public class NewDownloadDialogHelper
    {
        public static void CreateAndShowDialog(IAppService app, IAppUIController appUi,
            INewDownloadDialogSkeleton window, Message? message = null,
            Action? destroyCallback = null)
        {
            window.DestroyEvent += (_, _) => destroyCallback?.Invoke();
            window.SetFolderValues(CommonUtils.GetFolderValues());
            if (Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto)
            {
                window.SeletedFolderIndex = 0;
            }
            else
            {
                var index = CommonUtils.GetFolderValues().ToList().IndexOf(Config.Instance.UserSelectedDownloadFolder);
                if (index > 1)
                {
                    window.SeletedFolderIndex = index;
                }
                else
                {
                    Config.Instance.FolderSelectionMode = FolderSelectionMode.Auto;
                    window.SeletedFolderIndex = 0;
                }
            }
            //window.SeletedFolderIndex = Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto ? 0 : 2;

            var fileName = string.Empty;

            if (message != null)
            {
                window.Url = message.Url;
                fileName = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url)));
                window.SelectedFileName = fileName;

                var contentLength = 0L;
                var header = message.GetResponseHeaderFirstValue("Content-Length");
                if (!string.IsNullOrEmpty(header))
                {
                    try
                    {
                        contentLength = Int64.Parse(header);
                    }
                    catch { }
                }
                window.SetFileSizeText(contentLength > 0 ? Helpers.FormatSize(contentLength) : "---");
            }
            else
            {
                var url = appUi.GetUrlFromClipboard();
                if (!string.IsNullOrEmpty(url))
                {
                    window.Url = url;
                    window.SelectedFileName = Helpers.SanitizeFileName(Helpers.GetFileName(new Uri(url)));
                }
                window.UrlChangedEvent += (sender, args) =>
                {
                    if (Helpers.IsUriValid(window.Url))
                    {
                        window.SelectedFileName = Helpers.SanitizeFileName(Helpers.GetFileName(new Uri(window.Url)));
                        fileName = window.SelectedFileName;
                    }
                };
            }

            window.FileBrowsedEvent += CommonUtils.OnFileBrowsed;
            window.DropdownSelectionChangedEvent += CommonUtils.OnDropdownSelectionChanged;

            window.UrlBlockedEvent += (sender, args) =>
            {
                if (Helpers.IsUriValid(window.Url))
                {
                    var url = new Uri(window.Url);
                    var blockedHost = new List<string>();
                    blockedHost.AddRange(Config.Instance.BlockedHosts);
                    blockedHost.Add(url.Host);
                    Config.Instance.BlockedHosts = blockedHost.ToArray();
                    Config.SaveConfig();
                    app.ApplyConfig();
                    window.DisposeWindow();
                }
            };

            window.DownloadClicked += (a, b) =>
            {
                OnDownloadClicked(app, window, fileName, CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex), message, true);
            };

            window.DownloadLaterClicked += (a, b) =>
            {
                OnDownloadClicked(app, window, fileName, CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex), message, false, b.QueueId);
            };

            window.QueueSchedulerClicked += (s, e) =>
            {
                appUi.ShowQueueWindow(s);
            };

            window.ShowWindow();
        }

        //public static void CreateAndShowDialog(IApp app, IAppUI appUi, INewDownloadDialogSkeleton window)
        //{
        //    window.FolderSelectionMode = Config.FolderSelectionMode;
        //    window.ConflictResolution = Config.FileConflictResolution;
        //    var url = appUi.GetUrlFromClipboard();
        //    if (url != null)
        //    {
        //        window.Url = url;
        //        window.File = Helpers.GetFileName(new Uri(url));
        //    }
        //    var file = string.Empty;
        //    window.UrlChangedEvent += (sender, args) =>
        //    {
        //        if (Helpers.IsUriValid(window.Url))
        //        {
        //            window.File = Helpers.GetFileName(new Uri(window.Url));
        //            file = window.File;
        //        }
        //    };

        //    string selectedFolder = null;
        //    window.BrowseClicked += (a, b) =>
        //    {
        //        var selectedFile = window.SelectFile();
        //        if (selectedFile != null)
        //        {
        //            CommonUtils.GetFolder(selectedFile, window, ref selectedFolder);
        //        }
        //    };

        //    window.DownloadClicked += (a, b) =>
        //    {
        //        OnDownloadClicked(app, appUi, window, file, selectedFolder, null, true);
        //    };

        //    window.ShowWindow();
        //}

        private static void OnDownloadClicked(IAppService app, INewDownloadDialogSkeleton window,
            string fileName, string? selectedFolder, Message message, bool startImmediately, string? queueId = null)
        {

            if (!Helpers.IsUriValid(window.Url))
            {
                window.ShowMessageBox(TextResource.GetText("MSG_INVALID_URL"));
                return;
            }
            if (string.IsNullOrEmpty(window.SelectedFileName))
            {
                window.ShowMessageBox(TextResource.GetText("MSG_NO_FILE"));
                return;
            }

            var contentLength = 0L;
            var header = message?.GetResponseHeaderFirstValue("Content-Length") ?? message?.GetResponseHeaderFirstValue("content-length");// message?.ResponseHeaders?.Keys.Where(key => key.Equals("content-length", StringComparison.InvariantCultureIgnoreCase));
            if (!string.IsNullOrEmpty(header))
            {
                try
                {
                    contentLength = Int64.Parse(header);
                }
                catch { }
            }

            app.StartDownload(
                new SingleSourceHTTPDownloadInfo
                {
                    Uri = window.Url,
                    Headers = message?.RequestHeaders,
                    Cookies = message?.Cookies,
                    ContentLength = contentLength
                },
                Helpers.SanitizeFileName(window.SelectedFileName),
                window.SelectedFileName != fileName ? FileNameFetchMode.None : FileNameFetchMode.FileNameAndExtension,
                selectedFolder,
                startImmediately,
                window.Authentication, window.Proxy ?? Config.Instance.Proxy,
                window.EnableSpeedLimit ? window.SpeedLimit : 0, queueId, false);

            //var http = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo
            //{
            //    Uri = window.Url,
            //    Headers = message?.RequestHeaders,
            //    Cookies = message?.Cookies,
            //    ContentLength = contentLength
            //});

            //if (window.File != fileName)
            //{
            //    http.SetFileName(Helpers.SanitizeFileName(window.File), FileNameFetchMode.None);
            //}
            //else
            //{
            //    http.SetFileName(Helpers.SanitizeFileName(window.File), FileNameFetchMode.FileNameAndExtension);
            //}

            //if (window.FolderSelectionMode == FolderSelectionMode.Manual)
            //{
            //    http.SetTargetDirectory(selectedFolder);
            //}

            //app.StartDownload(http, startImmediately);

            window.DisposeWindow();
        }
    }
}
