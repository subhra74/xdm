using System;
using System.Collections.Generic;
using System.Text;
using TraceLog;
using XDM.Core.Collections;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;

namespace XDM.Core.BrowserMonitoring
{
    public class VideoTracker : IVideoTracker
    {
        private GenericOrderedDictionary<string, KeyValuePair<DualSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>> ytVideoList = new();
        private GenericOrderedDictionary<string, KeyValuePair<SingleSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>> videoList = new();
        private GenericOrderedDictionary<string, KeyValuePair<MultiSourceHLSDownloadInfo, StreamingVideoDisplayInfo>> hlsVideoList = new();
        private GenericOrderedDictionary<string, KeyValuePair<MultiSourceDASHDownloadInfo, StreamingVideoDisplayInfo>> dashVideoList = new();

        //public void ClearVideoList()
        //{
        //    ytVideoList.Clear();
        //    hlsVideoList.Clear();
        //    dashVideoList.Clear();
        //    videoList.Clear();
        //    ApplicationContext.BroadcastConfigChange();
        //}

        //public bool IsFFmpegRequiredForDownload(string id)
        //{
        //    return ytVideoList.ContainsKey(id) || dashVideoList.ContainsKey(id) || hlsVideoList.ContainsKey(id);
        //}

        //public void StartVideoDownload(string videoId,
        //    string name,
        //    string? folder,
        //    bool startImmediately,
        //    AuthenticationInfo? authentication,
        //    ProxyInfo? proxyInfo,
        //    int maxSpeedLimit,
        //    string? queueId,
        //    bool convertToMp3 = false //only applicable for dual source http downloads
        //    )
        //{
        //    //IBaseDownloader downloader = null;
        //    if (ytVideoList.ContainsKey(videoId))
        //    {
        //        ApplicationContext.CoreService.StartDownload(ytVideoList[videoId].Key, name, FileNameFetchMode.ExtensionOnly,
        //                folder, startImmediately, authentication, proxyInfo, queueId, false);
        //    }
        //    else if (videoList.ContainsKey(videoId))
        //    {
        //        ApplicationContext.CoreService.StartDownload(videoList[videoId].Key, name, convertToMp3 ? FileNameFetchMode.None : FileNameFetchMode.ExtensionOnly,
        //            folder, startImmediately, authentication, proxyInfo, queueId, convertToMp3);
        //    }
        //    else if (hlsVideoList.ContainsKey(videoId))
        //    {
        //        ApplicationContext.CoreService.StartDownload(hlsVideoList[videoId].Key, name, FileNameFetchMode.ExtensionOnly,
        //            folder, startImmediately, authentication, proxyInfo, queueId, false);
        //    }
        //    else if (dashVideoList.ContainsKey(videoId))
        //    {
        //        ApplicationContext.CoreService.StartDownload(dashVideoList[videoId].Key, name, FileNameFetchMode.ExtensionOnly,
        //            folder, startImmediately, authentication, proxyInfo, queueId, false);
        //    }
        //}

        //public List<(string ID, string File, string DisplayName, DateTime Time)> GetVideoList(bool encode = true)
        //{
        //    lock (this)
        //    {
        //        var list = new List<(string ID, string File, string DisplayName, DateTime Time)>();
        //        foreach (var e in ytVideoList)
        //        {
        //            list.Add((e.Key, encode ? Helpers.EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
        //        }
        //        foreach (var e in videoList)
        //        {
        //            list.Add((e.Key, encode ? Helpers.EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
        //        }
        //        foreach (var e in hlsVideoList)
        //        {
        //            list.Add((e.Key, encode ? Helpers.EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
        //        }
        //        foreach (var e in dashVideoList)
        //        {
        //            list.Add((e.Key, encode ? Helpers.EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
        //        }
        //        list.Sort((a, b) => a.Time.CompareTo(b.Time));
        //        return list;
        //    }
        //}

        public void AddVideoNotifications(IEnumerable<KeyValuePair<DualSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    ytVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotifications(IEnumerable<KeyValuePair<SingleSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    videoList.Add(Guid.NewGuid().ToString(), info);
                }
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotifications(IEnumerable<KeyValuePair<MultiSourceHLSDownloadInfo, StreamingVideoDisplayInfo>> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    hlsVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotifications(IEnumerable<KeyValuePair<MultiSourceDASHDownloadInfo, StreamingVideoDisplayInfo>> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    dashVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, DualSourceHTTPDownloadInfo info)
        {
            lock (this)
            {
                ytVideoList.Add(Guid.NewGuid().ToString(), new KeyValuePair<DualSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>(info, displayInfo));
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, SingleSourceHTTPDownloadInfo info)
        {
            lock (this)
            {
                videoList.Add(Guid.NewGuid().ToString(), new KeyValuePair<SingleSourceHTTPDownloadInfo, StreamingVideoDisplayInfo>(info, displayInfo));
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceHLSDownloadInfo info)
        {
            lock (this)
            {
                var id = Guid.NewGuid().ToString();
                hlsVideoList.Add(id, new KeyValuePair<MultiSourceHLSDownloadInfo, StreamingVideoDisplayInfo>(info, displayInfo));
                ApplicationContext.BroadcastConfigChange();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceDASHDownloadInfo info)
        {
            lock (this)
            {
                var id = Guid.NewGuid().ToString();
                Log.Debug("DASH video added with id: " + id);
                dashVideoList.Add(id, new KeyValuePair<MultiSourceDASHDownloadInfo, StreamingVideoDisplayInfo>(info, displayInfo));
                ApplicationContext.BroadcastConfigChange();
            }
        }

        //public void AddVideoDownload(string videoId)
        //{
        //    var name = string.Empty;
        //    var size = 0L;
        //    var contentType = string.Empty;
        //    var valid = false;
        //    if (ytVideoList.ContainsKey(videoId))
        //    {
        //        if (ApplicationContext.LinkRefresher.LinkAccepted(ytVideoList[videoId].Key)) return;
        //        name = ytVideoList[videoId].Key.File;
        //        size = ytVideoList[videoId].Value.Size;
        //        contentType = ytVideoList[videoId].Key.ContentType1;
        //        valid = true;
        //    }
        //    else if (videoList.ContainsKey(videoId))
        //    {
        //        if (ApplicationContext.LinkRefresher.LinkAccepted(videoList[videoId].Key)) return;
        //        name = videoList[videoId].Key.File;
        //        size = videoList[videoId].Value.Size;
        //        contentType = videoList[videoId].Key.ContentType;
        //        valid = true;
        //    }
        //    else if (hlsVideoList.ContainsKey(videoId))
        //    {
        //        Log.Debug("Download HLS video added with id: " + videoId);
        //        name = hlsVideoList[videoId].Key.File;
        //        valid = true;
        //        try
        //        {
        //            contentType = hlsVideoList[videoId].Key.ContentType;
        //        }
        //        catch (Exception ex)
        //        {
        //            Log.Debug(ex, ex.Message);
        //        }
        //    }
        //    else if (dashVideoList.ContainsKey(videoId))
        //    {
        //        Log.Debug("Download DASH video added with id: " + videoId);
        //        name = dashVideoList[videoId].Key.File;
        //        contentType = dashVideoList[videoId].Key.ContentType;
        //        valid = true;
        //    }
        //    if (valid)
        //    {
        //        if (Config.Instance.StartDownloadAutomatically && IsFFmpegOK(videoId))
        //        {
        //            StartVideoDownload(
        //                videoId, FileHelper.SanitizeFileName(name),
        //                null, true, null, Config.Instance.Proxy,
        //            Helpers.GetSpeedLimit(), null);
        //        }
        //        else
        //        {
        //            ApplicationContext.Application.ShowVideoDownloadDialog(videoId, name, size, contentType);
        //        }
        //    }
        //}

        //public static bool IsFFmpegOK(string id)
        //{
        //    if (!ApplicationContext.VideoTracker.IsFFmpegRequiredForDownload(id)) return true;
        //    return FFmpegMediaProcessor.IsFFmpegInstalled();
        //}
    }
}
