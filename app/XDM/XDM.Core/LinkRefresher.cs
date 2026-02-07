using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core.Downloader.Progressive;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;

namespace XDM.Core
{
    public class LinkRefresher : ILinkRefresher
    {
        private HTTPDownloaderBase? refreshLinkCandidate;
        public event EventHandler? RefreshedLinkReceived;

        public bool LinkAccepted(Message message)
        {
            if (refreshLinkCandidate != null && IsMatchingSingleSourceLink(message))
            {
                HandleSingleSourceLinkRefresh(message);
                return true;
            }
            return false;
        }

        public bool LinkAccepted(SingleSourceHTTPDownloadInfo info)
        {
            if (refreshLinkCandidate != null && IsMatchingSingleSourceLink(info))
            {
                HandleSingleSourceLinkRefresh(info);
                return true;
            }
            return false;
        }

        public bool LinkAccepted(DualSourceHTTPDownloadInfo info)
        {
            if (refreshLinkCandidate != null && IsMatchingDualSourceLink(info))
            {
                HandleDualSourceLinkRefresh(info);
                return true;
            }
            return false;
        }

        private bool IsMatchingSingleSourceLink(Message message)
        {
            if (!(refreshLinkCandidate is SingleSourceHTTPDownloader)) return false;
            var contentLength = 0L;
            var header = message.ResponseHeaders.Keys.Where(key => key.Equals("content-length", StringComparison.InvariantCultureIgnoreCase));
            if (header.Count() == 1)
            {
                contentLength = Int64.Parse(message.ResponseHeaders[header.First()][0]);
            }
            return refreshLinkCandidate.FileSize == contentLength && refreshLinkCandidate.FileSize > 0;
        }

        private bool IsMatchingSingleSourceLink(SingleSourceHTTPDownloadInfo info)
        {
            if (!(refreshLinkCandidate is SingleSourceHTTPDownloader)) return false;
            var contentLength = info.ContentLength;
            return refreshLinkCandidate.FileSize == contentLength && refreshLinkCandidate.FileSize > 0;
        }

        private void HandleSingleSourceLinkRefresh(Message message)
        {
            if (refreshLinkCandidate == null) return;
            var info = new SingleSourceHTTPDownloadInfo
            {
                Uri = message.Url,
                Headers = message?.RequestHeaders,
                Cookies = message?.Cookies
            };
            ((SingleSourceHTTPDownloader)refreshLinkCandidate).SetDownloadInfo(info);
            refreshLinkCandidate = null;
            RefreshedLinkReceived?.Invoke(this, EventArgs.Empty);
            ClearRefreshRecivedEvents();
        }

        private void HandleSingleSourceLinkRefresh(SingleSourceHTTPDownloadInfo info)
        {
            if (refreshLinkCandidate == null) return;
            ((SingleSourceHTTPDownloader)refreshLinkCandidate).SetDownloadInfo(info);
            refreshLinkCandidate = null;
            RefreshedLinkReceived?.Invoke(this, EventArgs.Empty);
            ClearRefreshRecivedEvents();
        }

        private bool IsMatchingDualSourceLink(DualSourceHTTPDownloadInfo info)
        {
            if (!(refreshLinkCandidate is DualSourceHTTPDownloader)) return false;
            return info.ContentLength > 0 &&
                ((DualSourceHTTPDownloader)refreshLinkCandidate).FileSize == info.ContentLength;
        }

        private void HandleDualSourceLinkRefresh(DualSourceHTTPDownloadInfo info)
        {
            if (refreshLinkCandidate == null) return;
            ((DualSourceHTTPDownloader)refreshLinkCandidate).SetDownloadInfo(info);
            refreshLinkCandidate = null;
            RefreshedLinkReceived?.Invoke(this, EventArgs.Empty);
            ClearRefreshRecivedEvents();
        }

        private void ClearRefreshRecivedEvents()
        {
            var delegates = RefreshedLinkReceived?.GetInvocationList();
            if (delegates == null) return;
            foreach (Delegate d in delegates)
            {
                RefreshedLinkReceived -= (EventHandler)d;
            }
        }

        public void AddToWatchList(HTTPDownloaderBase downloader)
        {
            this.refreshLinkCandidate = downloader;
        }

        public void ClearWatchList()
        {
            this.refreshLinkCandidate = null;
        }
    }
}
