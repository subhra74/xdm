using System.IO;
using System.Linq;
using Translations;
using XDM.Core;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;

namespace XDM.Core.UI
{
    public class NewVideoDownloadDialogHelper
    {
        public static void ShowVideoDownloadDialog(INewVideoDownloadDialog window,
            string id, string name, long size, string? contentType)
        {
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
            window.SelectedFileName = FileHelper.SanitizeFileName(name);
            window.FileSize = FormattingHelper.FormatSize(size);

            window.FileBrowsedEvent += CommonUtils.OnFileBrowsed;
            window.DropdownSelectionChangedEvent += CommonUtils.OnDropdownSelectionChanged;

            if (!string.IsNullOrEmpty(contentType))
            {
                var mime = contentType!.ToLowerInvariant();
                if (mime.StartsWith("audio"))
                {
                    if (!(mime.Contains("mpeg") || mime.Contains("mp3")))
                    {
                        window.ShowMp3Checkbox = true;
                    }
                }
            }

            window.DownloadClicked += (a, b) =>
            {
                if (string.IsNullOrEmpty(window.SelectedFileName))
                {
                    window.ShowMessageBox(TextResource.GetText("MSG_NO_FILE"));
                    return;
                }
                if (ApplicationContext.VideoTracker.IsFFmpegRequiredForDownload(id) && !IsFFmpegInstalled())
                {
                    if (ApplicationContext.Application.Confirm(window, TextResource.GetText("MSG_DOWNLOAD_FFMPEG")))
                    {
                        ApplicationContext.Application.InstallLatestFFmpeg();
                    }
                    return;
                }
                var name = FileHelper.SanitizeFileName(window.SelectedFileName);
                if (window.IsMp3CheckboxChecked)
                {
                    name = AddMp3Extension(name);
                }
                ApplicationContext.VideoTracker.StartVideoDownload(id, name,
                    CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex),
                    true,
                    window.Authentication,
                    window.Proxy ?? Config.Instance.Proxy,
                    window.EnableSpeedLimit ? window.SpeedLimit : 0,
                    null,
                    window.IsMp3CheckboxChecked);
                window.DisposeWindow();
            };

            window.DownloadLaterClicked += (a, b) =>
            {
                if (string.IsNullOrEmpty(window.SelectedFileName))
                {
                    window.ShowMessageBox(TextResource.GetText("MSG_NO_FILE"));
                    return;
                }
                var name = FileHelper.SanitizeFileName(window.SelectedFileName);
                if (window.IsMp3CheckboxChecked)
                {
                    name = AddMp3Extension(name);
                }
                ApplicationContext.VideoTracker.StartVideoDownload(id, name,
                    CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex),
                    false,
                    window.Authentication,
                    window.Proxy ?? Config.Instance.Proxy,
                    window.EnableSpeedLimit ? window.SpeedLimit : 0,
                    b.QueueId,
                    window.IsMp3CheckboxChecked);
                window.DisposeWindow();
            };

            window.CancelClicked += (a, b) =>
            {
                window.DisposeWindow();
            };

            window.QueueSchedulerClicked += (s, e) =>
            {
                ApplicationContext.Application.ShowQueueWindow(s);
            };

            window.ShowWindow();
        }

        private static string AddMp3Extension(string name)
        {
            return $"{Path.GetFileNameWithoutExtension(name)}.mp3";
        }

        private static bool IsFFmpegInstalled()
        {
            try
            {
                FFmpegMediaProcessor.FindFFmpegBinary();
            }
            catch (FileNotFoundException)
            {
                return false;
            }
            return true;
        }

    }
}
