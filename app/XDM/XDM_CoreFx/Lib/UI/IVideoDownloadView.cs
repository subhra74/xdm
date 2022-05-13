using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.Lib.UI
{
    public interface IVideoDownloadView
    {
        string DownloadLocation { get; set; }
        string Url { get; set; }
        event EventHandler? CancelClicked;
        event EventHandler? WindowClosed;
        event EventHandler? BrowseClicked;
        event EventHandler? SearchClicked;
        event EventHandler? DownloadClicked;
        event EventHandler? DownloadLaterClicked;
        void SwitchToInitialPage();
        void SwitchToProcessingPage();
        void SwitchToFinalPage();
        string? SelectFolder();
        public void SetVideoResultList(IEnumerable<string> items, IEnumerable<string> formats);
        public int SelectedFormat { get; set; }
        public IEnumerable<int> SelectedRows { get; }
        public int SelectedItemCount { get; }
        public void CloseWindow();
        public void ShowWindow();
    }
}
