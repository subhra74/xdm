using System;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Downloader
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
