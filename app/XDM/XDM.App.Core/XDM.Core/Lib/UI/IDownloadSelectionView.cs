using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;

namespace XDM.Core.Lib.UI
{
    public interface IDownloadSelectionView
    {
        event EventHandler? BrowseClicked;
        event EventHandler? DownloadClicked;
        event EventHandler? QueueSchedulerClicked;
        event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;

        string? SelectFolder();
        void CloseWindow();
        void ShowWindow();
        void SetData(FileNameFetchMode mode, IEnumerable<object> downloads,
            Func<object, IDownloadEntryWrapper, bool> populateEntryWrapper);

        string DownloadLocation { get; set; }
        AuthenticationInfo? Authentication { get; set; }
        ProxyInfo? Proxy { get; set; }
        int SpeedLimit { get; set; }
        bool EnableSpeedLimit { get; set; }
        int SelectedRowCount { get; }
        IEnumerable<IDownloadEntryWrapper> SelectedItems { get; }
    }

    public interface IDownloadEntryWrapper
    {
        public string Name { get; set; }
        public bool IsSelected { get; set; }
        public object DownloadEntry { get; set; }
        public string EntryType { get; set; }
    }
}
