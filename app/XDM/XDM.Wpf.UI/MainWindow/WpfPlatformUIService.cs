using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Windows;
using Translations;
using XDM.Core;
using XDM.Core.Downloader;
using XDM.Core.UI;
using XDM.Core.Util;
using XDM.Wpf.UI.Dialogs.BatchDownload;
using XDM.Wpf.UI.Dialogs.CompletedDialog;
using XDM.Wpf.UI.Dialogs.CredentialDialog;
using XDM.Wpf.UI.Dialogs.DownloadSelection;
using XDM.Wpf.UI.Dialogs.NewDownload;
using XDM.Wpf.UI.Dialogs.NewVideoDownload;
using XDM.Wpf.UI.Dialogs.ProgressWindow;
using XDM.Wpf.UI.Dialogs.PropertiesDialog;
using XDM.Wpf.UI.Dialogs.QueuesWindow;
using XDM.Wpf.UI.Dialogs.RefreshLink;
using XDM.Wpf.UI.Dialogs.Settings;
using XDM.Wpf.UI.Dialogs.SpeedLimiter;
using XDM.Wpf.UI.Dialogs.Updater;
using XDM.Wpf.UI.Dialogs.VideoDownloader;
using XDM.Wpf.UI.Win32;

namespace XDM.Wpf.UI
{
    public class WpfPlatformUIService : IPlatformUIService
    {
        private Window? window;

        public WpfPlatformUIService()
        {
            ApplicationContext.Initialized += ApplicationContext_Initialized;
        }

        private void ApplicationContext_Initialized(object sender, EventArgs e)
        {
            window = (Window)ApplicationContext.MainWindow;
        }

        private Window GetMainWindow()
        {
            return window!;
        }

        public void ShowSpeedLimiterWindow()
        {
            var window = new SpeedLimiterWindow();
            SpeedLimiterUIController.Run(window);
        }

        public string? SaveFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            var fc = new SaveFileDialog();
            if (!string.IsNullOrEmpty(initialPath))
            {
                fc.FileName = initialPath;
            }
            if (!string.IsNullOrEmpty(defaultExt))
            {
                fc.DefaultExt = defaultExt;
            }
            if (!string.IsNullOrEmpty(filter))
            {
                fc.Filter = filter;
            }
            var ret = fc.ShowDialog(GetMainWindow());
            if (ret.HasValue && ret.Value)
            {
                return fc.FileName;
            }
            return null;
        }

        public string? OpenFileDialog(string? initialPath, string? defaultExt, string? filter)
        {
            var fc = new OpenFileDialog();
            if (!string.IsNullOrEmpty(initialPath))
            {
                fc.FileName = initialPath;
            }
            if (!string.IsNullOrEmpty(defaultExt))
            {
                fc.DefaultExt = defaultExt;
            }
            if (!string.IsNullOrEmpty(filter))
            {
                fc.Filter = filter;
            }
            var ret = fc.ShowDialog(GetMainWindow());
            if (ret.HasValue && ret.Value)
            {
                return fc.FileName;
            }
            return null;
        }

        public void ShowRefreshLinkDialog(InProgressDownloadItem entry)
        {
            var dlg = new LinkRefreshWindow();
            var ret = LinkRefreshDialogUIController.RefreshLink(entry, dlg);
            if (!ret)
            {
                ShowMessageBox(GetMainWindow(), TextResource.GetText("NO_REFRESH_LINK"));
                return;
            }
        }

        public void ShowPropertiesDialog(DownloadItemBase ent, ShortState? state)
        {
            var propertiesWindow = new DownloadPropertiesWindow
            {
                FileName = ent.Name,
                Folder = ent.TargetDir ?? FileHelper.GetDownloadFolderByFileName(ent.Name),
                Address = ent.PrimaryUrl,
                FileSize = FormattingHelper.FormatSize(ent.Size),
                DateAdded = ent.DateAdded.ToLongDateString() + " " + ent.DateAdded.ToLongTimeString(),
                DownloadType = ent.DownloadType,
                Referer = ent.RefererUrl,
                Cookies = state?.Cookies ?? state?.Cookies1 ?? string.Empty,
                Headers = state?.Headers ?? state?.Headers1 ?? new Dictionary<string, List<string>>(),
                Owner = GetMainWindow()
            };
            propertiesWindow.ShowDialog(GetMainWindow());
        }

        public void ShowYoutubeDLDialog()
        {
            var ydlWindow = new VideoDownloaderWindow() { Owner = GetMainWindow() };
            var win = new VideoDownloaderUIController(ydlWindow);
            win.Run();
        }

        public void ShowBatchDownloadWindow()
        {
            var uvc = new BatchDownloadUIController(new BatchDownloadWindow { Owner = GetMainWindow() });
            uvc.Run();
        }

        public void ShowSettingsDialog(int page = 0)
        {
            var settings = new SettingsWindow() { Owner = GetMainWindow() };
            settings.ShowDialog(GetMainWindow());
        }

        public AuthenticationInfo? PromtForCredentials(object window, string message)
        {
            var wnd = (Window)window;
            var dlg = new CredentialsPromptDialog { PromptText = message ?? "Authentication required", Owner = wnd };
            var ret = dlg.ShowDialog(wnd);
            if (ret.HasValue && ret.Value)
            {
                return dlg.Credentials;
            }
            return null;
        }

        public void ShowBrowserMonitoringDialog()
        {
            var settings = new SettingsWindow(0) { Owner = GetMainWindow() };
            settings.ShowDialog(GetMainWindow());
        }

        public IUpdaterUI CreateUpdateUIDialog()
        {
            return new UpdaterWindow();
        }

        public void ShowMessageBox(object? window, string message)
        {
            var wnd = window is IApplication || window == GetMainWindow() || window == null ? GetMainWindow() : (Window)window;
            wnd.Dispatcher.Invoke(new Action(() =>
            {
                MessageBox.Show(wnd, message);
            }));
        }

        public void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads)
        {
            var dsvc = new DownloadSelectionUIController(new DownloadSelectionWindow(), FileNameFetchMode.FileNameAndExtension, downloads);
            dsvc.Run();
        }

        public IQueuesWindow CreateQueuesAndSchedulerWindow()
        {
            return new ManageQueueDialog()
            {
                Owner = GetMainWindow()
            };
        }

        public IQueueSelectionDialog CreateQueueSelectionDialog()
        {
            return new QueueSelectionWindow() { Owner = GetMainWindow() };
        }

        public IDownloadCompleteDialog CreateDownloadCompleteDialog()
        {
            return new DownloadCompleteWindow { };
        }

        public INewDownloadDialog CreateNewDownloadDialog(bool empty)
        {
            return new NewDownloadWindow() { IsEmpty = empty };
        }

        public INewVideoDownloadDialog CreateNewVideoDialog()
        {
            return new NewVideoDownloadWindow();
        }

        public IProgressWindow CreateProgressWindow(string downloadId)
        {
            return new DownloadProgressWindow
            {
                DownloadId = downloadId
            };
        }
    }
}
