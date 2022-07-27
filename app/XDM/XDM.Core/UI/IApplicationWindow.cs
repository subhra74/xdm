using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.UI;
using XDM.Core;
using XDM.Core.Downloader;

namespace XDM.Core.UI
{
    public interface IApplicationWindow
    {
        IEnumerable<FinishedDownloadItem> FinishedDownloads { get; set; }

        IEnumerable<InProgressDownloadItem> InProgressDownloads { get; set; }

        IInProgressDownloadRow? FindInProgressItem(string id);

        IFinishedDownloadRow? FindFinishedItem(string id);

        IList<IInProgressDownloadRow> SelectedInProgressRows { get; }

        IList<IFinishedDownloadRow> SelectedFinishedRows { get; }

        IButton NewButton { get; }

        IButton DeleteButton { get; }

        IButton PauseButton { get; }

        IButton ResumeButton { get; }

        IButton OpenFileButton { get; }

        IButton OpenFolderButton { get; }

        void AddToTop(InProgressDownloadItem entry);

        void AddToTop(FinishedDownloadItem entry);

        void SwitchToInProgressView();

        void ClearInProgressViewSelection();

        void SwitchToFinishedView();

        void ClearFinishedViewSelection();

        bool Confirm(object? window, string text);

        void ConfirmDelete(string text, out bool approved, out bool deleteFiles);

        IDownloadCompleteDialog CreateDownloadCompleteDialog();

        INewDownloadDialog CreateNewDownloadDialog(bool empty);

        INewVideoDownloadDialog CreateNewVideoDialog();

        IProgressWindow CreateProgressWindow(string downloadId);

        void RunOnUIThread(Action action);

        void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta);

        bool IsInProgressViewSelected { get; }

        void Delete(IInProgressDownloadRow row);

        void Delete(IFinishedDownloadRow row);

        void DeleteAllFinishedDownloads();

        void Delete(IEnumerable<IInProgressDownloadRow> rows);

        void Delete(IEnumerable<IFinishedDownloadRow> rows);

        string? GetUrlFromClipboard();

        AuthenticationInfo? PromtForCredentials(string message);

        void ShowUpdateAvailableNotification();

        void ShowMessageBox(object? window, string message);

        void OpenNewDownloadMenu();

        IMenuItem[] MenuItems { get; }

        Dictionary<string, IMenuItem> MenuItemMap { get; }

        string? SaveFileDialog(string? initialPath, string? defaultExt, string? filter);

        string? OpenFileDialog(string? initialPath, string? defaultExt, string? filter);

        void ShowRefreshLinkDialog(InProgressDownloadItem entry);

        void SetClipboardText(string text);

        void SetClipboardFile(string file);

        void ShowPropertiesDialog(DownloadItemBase ent, ShortState? state);

        void ShowYoutubeDLDialog();

        void ShowBatchDownloadWindow();

        void ShowSettingsDialog(int page = 0);

        void UpdateBrowserMonitorButton();

        void ShowBrowserMonitoringDialog();

        void UpdateParallalismLabel();

        IUpdaterUI CreateUpdateUIDialog();

        void ClearUpdateInformation();

        IQueuesWindow CreateQueuesAndSchedulerWindow();

        IQueueSelectionDialog CreateQueueSelectionDialog();

        void ShowDownloadSelectionWindow(FileNameFetchMode mode, IEnumerable<IRequestData> downloads);

        IPlatformClipboardMonitor GetClipboardMonitor();

        void ShowFloatingWidget();

        //void RunOnNewThread(Action action);

        event EventHandler<CategoryChangedEventArgs> CategoryChanged;

        event EventHandler InProgressContextMenuOpening;

        event EventHandler FinishedContextMenuOpening;

        event EventHandler SelectionChanged;

        event EventHandler NewDownloadClicked;

        event EventHandler YoutubeDLDownloadClicked;

        event EventHandler BatchDownloadClicked;

        event EventHandler SettingsClicked;

        event EventHandler ClearAllFinishedClicked;

        event EventHandler ExportClicked;

        event EventHandler ImportClicked;

        event EventHandler BrowserMonitoringButtonClicked;

        event EventHandler BrowserMonitoringSettingsClicked;

        event EventHandler UpdateClicked;

        event EventHandler HelpClicked;

        event EventHandler SupportPageClicked;

        event EventHandler BugReportClicked;

        event EventHandler CheckForUpdateClicked;

        event EventHandler SchedulerClicked;

        event EventHandler DownloadListDoubleClicked;

        event EventHandler WindowCreated;
    }

    public class CategoryChangedEventArgs : EventArgs
    {
        public int Level { get; set; }
        public int Index { get; set; }
        public Category? Category { get; set; }
    }
}
