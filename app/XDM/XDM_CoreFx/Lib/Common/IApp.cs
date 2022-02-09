using System;
using System.Collections.Generic;
using XDM.Core.Lib.Downloader;
using XDM.Core.Lib.Downloader.Adaptive.Dash;
using XDM.Core.Lib.Downloader.Adaptive.Hls;
using XDM.Core.Lib.Downloader.Progressive;
using XDM.Core.Lib.Downloader.Progressive.DualHttp;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.UI;

namespace XDM.Core.Lib.Common
{
    public interface IApp
    {
        public Version AppVerion { get; }
        public IAppUI AppUI { get; set; }
        public string HelpPage { get; }
        public string UpdatePage { get; }
        public string IssuePage { get; }
        public string ChromeExtensionUrl { get; }
        public string FirefoxExtensionUrl { get; }
        public string OperaExtensionUrl { get; }
        public string EdgeExtensionUrl { get; }

        public IList<UpdateInfo>? Updates { get; }
        public bool ComponentsInstalled { get; }
        public bool IsAppUpdateAvailable { get; }
        public string ComponentUpdateText { get; }
        string[] Args { get; set; }

        public void AddDownload(Message message);

        //public void StartDownload(IBaseDownloader download,
        //    bool startImmediately,
        //    AuthenticationInfo authentication,
        //    ProxyInfo proxyInfo,
        //    int maxSpeedLimit);

        public string StartDownload(SingleSourceHTTPDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit, string? queueId);

        public string StartDownload(DualSourceHTTPDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit, string? queueId);

        public string StartDownload(MultiSourceHLSDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit, string? queueId);

        public string StartDownload(MultiSourceDASHDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit, string? queueId);

        public void SaveState();
        public void SetUI(IListUI listUI);
        public void StopDownloads(IEnumerable<string> list, bool closeProgressWindow = false);
        public void ResumeDownload(Dictionary<string, BaseDownloadEntry> list, bool nonInteractive = false);
        public void ResumeNonInteractiveDownloads(IEnumerable<string> idList);
        //public void DeleteDownloads(List<string> list);
        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, DualSourceHTTPDownloadInfo info);
        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, SingleSourceHTTPDownloadInfo info);
        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, MultiSourceHLSDownloadInfo info);
        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, MultiSourceDASHDownloadInfo info);
        public void AddVideoNotifications(IEnumerable<(DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        public void AddVideoNotifications(IEnumerable<(SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        public void AddVideoNotifications(IEnumerable<(MultiSourceHLSDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        public void AddVideoNotifications(IEnumerable<(MultiSourceDASHDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications);
        public List<(string ID, string File, string DisplayName, DateTime Time)> GetVideoList(bool encode = true);
        public void AddVideoDownload(string videoId);
        //public void LoadDownloadList();
        public void StartVideoDownload(string videoId,
            string name,
            string? folder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit,
            string? queueId);
        //public void SaveInProgressList(IEnumerable<InProgresDownloadEntry> list);
        //public void SaveFinishedList(IEnumerable<FinishedDownloadEntry> list);
        public void ClearVideoList();
        public bool IsDownloadActive(string id);
        public int ActiveDownloadCount { get; }
        public void RenameDownload(string id, string folder, string file);
        public void WaitFromRefreshedLink(HTTPDownloaderBase downloader);
        public void ClearRefreshLinkCandidate();
        public event EventHandler RefreshedLinkReceived;
        public void StartClipboardMonitor();
        public void StopClipboardMonitor();
        public void ApplyConfig();
        public AuthenticationInfo? PromptForCredential(string id, string message);
        public void RestartDownload(BaseDownloadEntry entry);
        public string? GetPrimaryUrl(BaseDownloadEntry entry);
        public void RemoveDownload(BaseDownloadEntry entry, bool deleteDownloadedFile);
        public void ShowProgressWindow(string downloadId);
        public void HideProgressWindow(string id);
        public void Export(string path);
        public void Import(string path);
        bool IsFFmpegRequiredForDownload(string id);
        void UpdateSpeedLimit(string id, bool enable, int limit);
        bool GetLiveDownloadSpeedLimit(string id, out bool enabled, out int limit);
        void AddBatchLinks(List<Message> messages);
    }
}
