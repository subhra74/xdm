using System;
using XDM.Core;

namespace XDM.Core.Downloader
{
    public class DownloadFailedEventArgs : EventArgs
    {
        public DownloadFailedEventArgs(ErrorCode errorCode)
        {
            ErrorCode = errorCode;
        }
        public ErrorCode ErrorCode { get; }
    }
}
