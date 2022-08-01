using System;
using System.Linq;
using System.Collections.Generic;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public class NewDownloadDialogUIController
    {
        public static void CreateAndShowDialog(INewDownloadDialog window, Message? message = null,
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

            var fileName = string.Empty;

            if (message != null)
            {
                window.Url = message.Url;
                fileName = FileHelper.SanitizeFileName(message.File ?? FileHelper.GetFileName(new Uri(message.Url)));
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
                window.SetFileSizeText(contentLength > 0 ? FormattingHelper.FormatSize(contentLength) : "---");
            }
            else
            {
                var url = ApplicationContext.Application.GetUrlFromClipboard();
                if (!string.IsNullOrEmpty(url))
                {
                    window.Url = url;
                    window.SelectedFileName = FileHelper.SanitizeFileName(FileHelper.GetFileName(new Uri(url)));
                }
                window.UrlChangedEvent += (sender, args) =>
                {
                    if (Helpers.IsUriValid(window.Url))
                    {
                        window.SelectedFileName = FileHelper.SanitizeFileName(FileHelper.GetFileName(new Uri(window.Url)));
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
                    ApplicationContext.BroadcastConfigChange();
                    window.DisposeWindow();
                }
            };

            window.DownloadClicked += (a, b) =>
            {
                OnDownloadClicked(window, fileName, CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex), message, true);
            };

            window.DownloadLaterClicked += (a, b) =>
            {
                OnDownloadClicked(window, fileName, CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex), message, false, b.QueueId);
            };

            window.QueueSchedulerClicked += (s, e) =>
            {
                ApplicationContext.Application.ShowQueueWindow(s);
            };

            window.ShowWindow();
        }

        //public static void CreateAndShowDialog(IApp ApplicationContext.Core, IAppUI appUi, INewDownloadDialogSkeleton window)
        //{
        //    window.FolderSelectionMode = Config.FolderSelectionMode;
        //    window.ConflictResolution = Config.FileConflictResolution;
        //    var url = appUi.GetUrlFromClipboard();
        //    if (url != null)
        //    {
        //        window.Url = url;
        //        window.File = FileHelper.GetFileName(new Uri(url));
        //    }
        //    var file = string.Empty;
        //    window.UrlChangedEvent += (sender, args) =>
        //    {
        //        if (Helpers.IsUriValid(window.Url))
        //        {
        //            window.File = FileHelper.GetFileName(new Uri(window.Url));
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
        //        OnDownloadClicked(ApplicationContext.Core, appUi, window, file, selectedFolder, null, true);
        //    };

        //    window.ShowWindow();
        //}

        private static void OnDownloadClicked(INewDownloadDialog window,
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

            ApplicationContext.CoreService.StartDownload(
                new SingleSourceHTTPDownloadInfo
                {
                    Uri = window.Url,
                    Headers = message?.RequestHeaders,
                    Cookies = message?.Cookies,
                    ContentLength = contentLength
                },
                FileHelper.SanitizeFileName(window.SelectedFileName),
                window.SelectedFileName != fileName ? FileNameFetchMode.None : FileNameFetchMode.FileNameAndExtension,
                selectedFolder,
                startImmediately,
                window.Authentication, window.Proxy ?? Config.Instance.Proxy, queueId, false);

            //var http = new SingleSourceHTTPDownloader(new SingleSourceHTTPDownloadInfo
            //{
            //    Uri = window.Url,
            //    Headers = message?.RequestHeaders,
            //    Cookies = message?.Cookies,
            //    ContentLength = contentLength
            //});

            //if (window.File != fileName)
            //{
            //    http.SetFileName(FileHelper.SanitizeFileName(window.File), FileNameFetchMode.None);
            //}
            //else
            //{
            //    http.SetFileName(FileHelper.SanitizeFileName(window.File), FileNameFetchMode.FileNameAndExtension);
            //}

            //if (window.FolderSelectionMode == FolderSelectionMode.Manual)
            //{
            //    http.SetTargetDirectory(selectedFolder);
            //}

            //ApplicationContext.Core.StartDownload(http, startImmediately);

            window.DisposeWindow();
        }
    }
}
