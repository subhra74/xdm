using System.Collections.Generic;
using XDM.Core;

namespace XDM.Core.Downloader.Adaptive
{
    class CancelRequestor : ICancelRequster
    {
        private List<HttpChunkDownloader> downloaders = new();
        private CancelFlag _cancellationToken;
        public ErrorCode Error { get; private set; } = ErrorCode.None;

        public CancelRequestor(CancelFlag cancellationToken)
        {
            _cancellationToken = cancellationToken;
        }

        public void CancelWithFatal(ErrorCode error)
        {
            CancelAll();
            this.Error = error;
            if (!_cancellationToken.IsCancellationRequested)
            {
                _cancellationToken.Cancel();
            }
        }

        public void NotifyTransientFailure()
        {
            lock (this)
            {
                foreach (var downloader in downloaders)
                {
                    if (!downloader.TransientFailure)
                    {
                        return;
                    }
                }
                CancelWithFatal(ErrorCode.Generic);
            }
        }

        public void RegisterThread(HttpChunkDownloader chunkDownloader)
        {
            lock (this)
            {
                downloaders.Add(chunkDownloader);
            }
        }

        public void UnRegisterThread(HttpChunkDownloader chunkDownloader)
        {
            lock (this)
            {
                downloaders.Remove(chunkDownloader);
            }
        }

        public void CancelAll()
        {
            lock (this)
            {
                var list = new List<HttpChunkDownloader>(downloaders);
                foreach (var downloader in list)
                {
                    downloader.Cancel();
                }
            }
        }
    }
}
