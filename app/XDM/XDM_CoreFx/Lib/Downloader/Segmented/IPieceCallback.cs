using System;
using System.Collections.Generic;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Clients.Http;

namespace XDM.Core.Lib.Common.Segmented
{
    public interface IPieceCallback
    {
        public bool IsFirstRequest(StreamType streamType);
        public bool IsFileChangedOnServer(StreamType streamType, long streamSize, DateTime? lastModified);
        public Piece GetPiece(string pieceId);
        public (
            Dictionary<string, List<string>> Headers,
            Dictionary<string, string> Cookies,
            Uri Url, AuthenticationInfo? Authentication,
            ProxyInfo? Proxy)?
            GetHeaderUrlAndCookies(string pieceId);
        public IHttpClient? GetSharedHttpClient(string pieceId);
        public void PieceConnected(string pieceId, ProbeResult? result);
        public string GetPieceFile(string pieceId);
        public void UpdateDownloadedBytesCount(string pieceId, long bytes);
        public bool ContinueAdjacentPiece(string pieceId, long maxByteRange);
        public void PieceDownloadFailed(string pieceId, ErrorCode error);
        public void PieceDownloadFinished(string pieceId);
        public void ThrottleIfNeeded();
        public bool IsTextRedirectionAllowed();
    }
}
