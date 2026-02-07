using System;
using XDM.Core.Downloader.Progressive;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;

namespace XDM.Core
{
    public interface ILinkRefresher
    {
        event EventHandler? RefreshedLinkReceived;

        void AddToWatchList(HTTPDownloaderBase downloader);
        void ClearWatchList();
        bool LinkAccepted(Message message);
        bool LinkAccepted(SingleSourceHTTPDownloadInfo info);
        bool LinkAccepted(DualSourceHTTPDownloadInfo info);
    }
}