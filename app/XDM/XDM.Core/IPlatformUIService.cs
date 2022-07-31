using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core.Downloader;
using XDM.Core.UI;

namespace XDM.Core
{
    public interface IPlatformUIService
    {
        IDownloadCompleteDialog CreateDownloadCompleteDialog();

        INewDownloadDialog CreateNewDownloadDialog(bool empty);

        INewVideoDownloadDialog CreateNewVideoDialog();

        IProgressWindow CreateProgressWindow(string downloadId);

        AuthenticationInfo? PromtForCredentials(object window, string message);

        void ShowMessageBox(object? window, string message);

        string? SaveFileDialog(string? initialPath, string? defaultExt, string? filter);

        string? OpenFileDialog(string? initialPath, string? defaultExt, string? filter);

        void ShowRefreshLinkDialog(InProgressDownloadItem entry);

        void ShowPropertiesDialog(DownloadItemBase ent, ShortState? state);

        void ShowYoutubeDLDialog();

        void ShowBatchDownloadWindow();

        void ShowSettingsDialog(int page = 0);

        void ShowBrowserMonitoringDialog();

        IUpdaterUI CreateUpdateUIDialog();

        IQueuesWindow CreateQueuesAndSchedulerWindow();

        IQueueSelectionDialog CreateQueueSelectionDialog();

        void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads);

        void ShowSpeedLimiterWindow();
    }
}
