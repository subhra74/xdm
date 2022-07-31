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

        void RunOnUIThread(Action action);

        void RunOnUIThread(Action<string, int, double, long> action, string id, int progress, double speed, long eta);

        bool IsInProgressViewSelected { get; }

        void Delete(IInProgressDownloadRow row);

        void Delete(IFinishedDownloadRow row);

        void DeleteAllFinishedDownloads();

        void Delete(IEnumerable<IInProgressDownloadRow> rows);

        void Delete(IEnumerable<IFinishedDownloadRow> rows);

        string? GetUrlFromClipboard();

        void ShowUpdateAvailableNotification();

        void OpenNewDownloadMenu();

        IMenuItem[] MenuItems { get; }

        Dictionary<string, IMenuItem> MenuItemMap { get; }

        void SetClipboardText(string text);

        void SetClipboardFile(string file);

        void UpdateBrowserMonitorButton();

        void ClearUpdateInformation();

        IPlatformClipboardMonitor GetClipboardMonitor();

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
