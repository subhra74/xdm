using System.IO;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common.MediaProcessor;
using XDM.Core.Lib.Util;

namespace XDM.Common.UI
{
    public class NewVideoDownloadDialogHelper
    {
        public static void ShowVideoDownloadDialog(IApp app, IAppUI appUi, INewVideoDownloadDialog window, string id, string name, long size)
        {
            window.SetFolderValues(CommonUtils.GetFolderValues());
            window.SeletedFolderIndex = Config.Instance.FolderSelectionMode == FolderSelectionMode.Auto ? 0 : 2;
            window.SelectedFileName = Helpers.SanitizeFileName(name);
            window.FileSize = Helpers.FormatSize(size);

            window.FileBrowsedEvent += CommonUtils.OnFileBrowsed;
            window.DropdownSelectionChangedEvent += CommonUtils.OnDropdownSelectionChanged;

            window.DownloadClicked += (a, b) =>
            {
                if (string.IsNullOrEmpty(window.SelectedFileName))
                {
                    appUi.ShowMessageBox(window, "No filename");
                    return;
                }
                if (app.IsFFmpegRequiredForDownload(id) && !IsFFmpegInstalled())
                {
                    if (appUi.Confirm(window, "Download FFmpeg?"))
                    {
                        appUi.InstallLatestFFmpeg();
                    }
                    return;
                }
                app.StartVideoDownload(id, Helpers.SanitizeFileName(window.SelectedFileName),
                    CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex),
                    true,
                    window.Authentication,
                    window.Proxy ?? Config.Instance.Proxy,
                    window.EnableSpeedLimit ? window.SpeedLimit : 0,
                    null);
                window.DisposeWindow();
            };

            window.DownloadLaterClicked += (a, b) =>
            {
                if (string.IsNullOrEmpty(window.SelectedFileName))
                {
                    appUi.ShowMessageBox(window, "No filename");
                    return;
                }
                app.StartVideoDownload(id, Helpers.SanitizeFileName(window.SelectedFileName),
                    CommonUtils.SelectedFolderFromIndex(window.SeletedFolderIndex),
                    false,
                    window.Authentication,
                    window.Proxy ?? Config.Instance.Proxy,
                    window.EnableSpeedLimit ? window.SpeedLimit : 0,
                    b.QueueId);
                window.DisposeWindow();
            };

            window.CancelClicked += (a, b) =>
            {
                window.DisposeWindow();
            };

            window.QueueSchedulerClicked += (s, e) =>
            {
                appUi.ShowQueueWindow(s);
            };

            window.ShowWindow();
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
