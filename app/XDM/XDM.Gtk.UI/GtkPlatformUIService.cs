using Gtk;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.UI;
using XDM.Core.Util;
using XDM.GtkUI.Dialogs;
using XDM.GtkUI.Dialogs.BatchWindow;
using XDM.GtkUI.Dialogs.DownloadComplete;
using XDM.GtkUI.Dialogs.DownloadSelection;
using XDM.GtkUI.Dialogs.LinkRefresh;
using XDM.GtkUI.Dialogs.NewDownload;
using XDM.GtkUI.Dialogs.NewVideoDownload;
using XDM.GtkUI.Dialogs.ProgressWindow;
using XDM.GtkUI.Dialogs.Properties;
using XDM.GtkUI.Dialogs.QueueScheduler;
using XDM.GtkUI.Dialogs.Settings;
using XDM.GtkUI.Dialogs.SpeedLimiter;
using XDM.GtkUI.Dialogs.Updater;
using XDM.GtkUI.Dialogs.VideoDownloader;
using XDM.GtkUI.Utils;

namespace XDM.GtkUI
{
    public class GtkPlatformUIService : IPlatformUIService
    {
        private MainWindow? window;
        private WindowGroup? windowGroup;

        public GtkPlatformUIService()
        {
            ApplicationContext.Initialized += ApplicationContext_Initialized;
        }

        private void ApplicationContext_Initialized(object? sender, EventArgs e)
        {
            window = (MainWindow)ApplicationContext.MainWindow;
            windowGroup = window.GetWindowGroup();
        }

        private Window GetMainWindow()
        {
            return window!;
        }

        private WindowGroup GetWindowGroup()
        {
            return windowGroup!;
        }

        public void ShowSpeedLimiterWindow()
        {
            var window = SpeedLimiterWindow.CreateFromGladeFile();
            SpeedLimiterUIController.Run(window);
        }

        public string? SaveFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            return GtkHelper.SaveFile(GetMainWindow(), initialPath);
        }

        public string? OpenFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            return GtkHelper.SelectFile(GetMainWindow());
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow()
        {
            return QueueSchedulerDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
        }

        public void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads)
        {
            var dsvc = new DownloadSelectionUIController(DownloadSelectionWindow.CreateFromGladeFile(),
                FileNameFetchMode.FileNameAndExtension, downloads);
            dsvc.Run();
        }

        public void ShowRefreshLinkDialog(InProgressDownloadItem entry)
        {
            var dlg = LinkRefreshWindow.CreateFromGladeFile();
            var ret = LinkRefreshDialogUIController.RefreshLink(entry, dlg);
            if (!ret)
            {
                GtkHelper.ShowMessageBox(GetMainWindow(), TextResource.GetText("NO_REFRESH_LINK"));
                return;
            }
        }

        public void ShowPropertiesDialog(DownloadItemBase ent, ShortState? state)
        {
            using var propWin = PropertiesDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
            propWin.FileName = ent.Name;
            propWin.Folder = ent.TargetDir ?? FileHelper.GetDownloadFolderByFileName(ent.Name);
            propWin.Address = ent.PrimaryUrl;
            propWin.FileSize = FormattingHelper.FormatSize(ent.Size);
            propWin.DateAdded = ent.DateAdded.ToLongDateString() + " " + ent.DateAdded.ToLongTimeString();
            propWin.DownloadType = ent.DownloadType;
            propWin.Referer = ent.RefererUrl;
            propWin.Cookies = state?.Cookies ?? state?.Cookies1 ?? string.Empty;
            propWin.Headers = state?.Headers ?? state?.Headers1 ?? new Dictionary<string, List<string>>();
            propWin.Run();
            propWin.Destroy();
            propWin.Dispose();
        }

        public void ShowYoutubeDLDialog()
        {
            var win = new VideoDownloaderUIController(VideoDownloaderWindow.CreateFromGladeFile());
            win.Run();
        }

        public void ShowBatchDownloadWindow()
        {
            var uvc = new BatchDownloadUIController(BatchDownloadWindow.CreateFromGladeFile(GetMainWindow()));
            uvc.Run();
        }

        public void ShowSettingsDialog(int page = 0)
        {
            using var win = SettingsDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
            win.SetActivePage(page);
            win.LoadConfig();
            win.Run();
            win.Destroy();
        }

        public AuthenticationInfo? PromtForCredentials(string message)
        {
            var dlg = CredentialsDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
            dlg.PromptText = message ?? "Authentication required";
            dlg.Run();
            if (dlg.Result)
            {
                return dlg.Credentials;
            }
            return null;
        }

        public void ShowBrowserMonitoringDialog()
        {
            ShowSettingsDialog(0);
        }

        public IUpdaterUI CreateUpdateUIDialog()
        {
            return UpdaterWindow.CreateFromGladeFile();
        }

        public void ShowMessageBox(object? window, string message)
        {
            if (window is not Window owner)
            {
                owner = GetMainWindow();
            }
            GtkHelper.ShowMessageBox(owner, message);
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow(IEnumerable<DownloadQueue> queues)
        {
            return QueueSchedulerDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
        }

        public IQueueSelectionDialog CreateQueueSelectionDialog()
        {
            var qsd = QueueSelectionDialog.CreateFromGladeFile(GetMainWindow(), GetWindowGroup());
            return qsd;
        }
        public IDownloadCompleteDialog CreateDownloadCompleteDialog()
        {
            var win = DownloadCompleteDialog.CreateFromGladeFile();
            return win;
        }

        public INewDownloadDialog CreateNewDownloadDialog(bool empty)
        {
            var window = NewDownloadWindow.CreateFromGladeFile();
            window.IsEmpty = empty;
            return window;
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            var window = NewVideoDownloadWindow.CreateFromGladeFile();
            return window;
        }

        public IProgressWindow CreateProgressWindow(string downloadId)
        {
            var prgWin = DownloadProgressWindow.CreateFromGladeFile();
            prgWin.DownloadId = downloadId;
            return prgWin;
        }

        public AuthenticationInfo? PromtForCredentials(object window, string message)
        {
            throw new NotImplementedException();
        }
    }
}
