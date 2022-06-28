using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.UI
{
    public interface IVideoDownloadView
    {
        string DownloadLocation { get; set; }
        string Url { get; set; }
        string? SelectedBrowser { get; }
        List<string> AllowedBrowsers { set; }
        event EventHandler? CancelClicked;
        event EventHandler? WindowClosed;
        event EventHandler? BrowseClicked;
        event EventHandler? SearchClicked;
        event EventHandler? DownloadClicked;
        event EventHandler? QueueSchedulerClicked;
        event EventHandler<DownloadLaterEventArgs>? DownloadLaterClicked;
        void SwitchToInitialPage();
        void SwitchToProcessingPage();
        void SwitchToFinalPage();
        void SwitchToErrorPage();
        string? SelectFolder();
        public void SetVideoResultList(IEnumerable<string> items, IEnumerable<string> formats);
        public int SelectedFormat { get; set; }
        public IEnumerable<int> SelectedRows { get; }
        public int SelectedItemCount { get; }
        public void CloseWindow();
        public void ShowWindow();
        AuthenticationInfo? Authentication { get; set; }
        ProxyInfo? Proxy { get; set; }
        int SpeedLimit { get; set; }
        bool EnableSpeedLimit { get; set; }
    }
}
