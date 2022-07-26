using System;
using System.Collections.Generic;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;

namespace XDM.Core.BrowserMonitoring
{
    public interface IVideoTracker
    {
        void AddVideoDownload(string videoId);
        void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, DualSourceHTTPDownloadInfo info);
        void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceDASHDownloadInfo info);
        void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceHLSDownloadInfo info);
        void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, SingleSourceHTTPDownloadInfo info);
        void AddVideoNotifications(IEnumerable<(DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        void AddVideoNotifications(IEnumerable<(MultiSourceDASHDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        void AddVideoNotifications(IEnumerable<(MultiSourceHLSDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        void AddVideoNotifications(IEnumerable<(SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        void ClearVideoList();
        List<(string ID, string File, string DisplayName, DateTime Time)> GetVideoList(bool encode = true);
        bool IsFFmpegRequiredForDownload(string id);
        void StartVideoDownload(string videoId, string name, string? folder, bool startImmediately, 
            AuthenticationInfo? authentication, ProxyInfo? proxyInfo, int maxSpeedLimit, string? queueId, bool convertToMp3 = false);
    }
}