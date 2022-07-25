using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Common.UI
{
    public interface IProgressWindow
    {
        public string FileNameText { get; set; }

        public string UrlText { get; set; }

        public string FileSizeText { get; set; }

        public string DownloadSpeedText { get; set; }

        public string DownloadETAText { get; set; }

        public int DownloadProgress { get; set; }

        public string DownloadId { get; set; }

        public void ShowProgressWindow();

        public void DownloadCancelled();

        public void DownloadFailed(ErrorDetails error);

        public void DownloadStarted();

        public void DestroyWindow();
    }

    public struct ErrorDetails
    {
        public int Code { get; set; }
        public string Message { get; set; }
    }
}
