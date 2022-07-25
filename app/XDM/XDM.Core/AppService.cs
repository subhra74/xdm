using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.UI;
using XDM.Core;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;
using XDM.Core.BrowserMonitoring;
using XDM.Core.Collections;
using System.Timers;
using TraceLog;
using Translations;
using XDM.Core.Downloader;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Progressive;
using XDM.Core.DataAccess;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

//using XDM.Core.Downloader.YT.Dash;

namespace XDM.Core
{
    public class AppService : IAppService
    {
        public Version AppVerion => new(8, 0, 0);
        private Dictionary<string, (IBaseDownloader Downloader, bool NonInteractive)> liveDownloads = new();
        private GenericOrderedDictionary<string, bool> queuedDownloads = new();
        private GenericOrderedDictionary<string, (DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> ytVideoList = new();
        private GenericOrderedDictionary<string, (SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> videoList = new();
        private GenericOrderedDictionary<string, (MultiSourceHLSDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> hlsVideoList = new();
        private GenericOrderedDictionary<string, (MultiSourceDASHDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> dashVideoList = new();
        private GenericOrderedDictionary<string, IProgressWindow> activeProgressWindows = new();
        private HTTPDownloaderBase refreshLinkCandidate;
        private Scheduler scheduler;
        public event EventHandler RefreshedLinkReceived;
        private NativeMessagingHostHandler nativeMessaging;
        private bool isClipboardMonitorActive = false;
        private string lastClipboardText;
        private Timer awakePingTimer;
        private readonly System.Threading.Timer UpdateCheckTimer;

        public IList<UpdateInfo>? Updates { get; private set; }
        public bool ComponentsInstalled { get; private set; }
        public bool IsAppUpdateAvailable => Updates?.Any(u => !u.IsExternal) ?? false;
        public bool IsComponentUpdateAvailable => Updates?.Any(u => u.IsExternal) ?? false;
        public string ComponentUpdateText => GetUpdateText();

        public int ActiveDownloadCount { get => liveDownloads.Count + queuedDownloads.Count; }

        public IUIService AppUI { get; set; }

        public string HelpPage => "https://subhra74.github.io/xdm/redirect-support.html";
        public string UpdatePage => $"https://subhra74.github.io/xdm/update-checker.html?v={AppVerion}";
        public string IssuePage => "https://subhra74.github.io/xdm/redirect-issue.html";
        public string ChromeExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=chrome";
        public string FirefoxExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=firefox";
        public string OperaExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=opera";
        public string EdgeExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=edge";

        public string[] Args { get; set; }

        public AppService()
        {
            //var configPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".xdman");
            //Directory.CreateDirectory(configPath);
            //Config.DataDir = configPath;
            //Config.LoadConfig();
            //TextResource.Load(Config.Instance.Language);

            awakePingTimer = new Timer(60000)
            {
                AutoReset = true
            };
            awakePingTimer.Elapsed += (a, b) => Helpers.SendKeepAlivePing();

            UpdateCheckTimer = new System.Threading.Timer(
                callback: a => CheckForUpdate(),
                state: null,
                dueTime: TimeSpan.FromSeconds(5),
                period: TimeSpan.FromHours(3));

            try
            {
                QueueManager.Load();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.ToString());
            }
        }

        public void StartClipboardMonitor()
        {
            Log.Debug("StartClipboardMonitor");
            if (isClipboardMonitorActive) return;
            var cm = AppUI.GetClipboardMonitor();
            if (Config.Instance.MonitorClipboard)
            {
                cm.StartClipboardMonitoring();
                isClipboardMonitorActive = true;
                cm.ClipboardChanged += Cm_ClipboardChanged;
            }
        }

        public void StopClipboardMonitor()
        {
            if (!isClipboardMonitorActive) return;
            var cm = AppUI.GetClipboardMonitor();
            cm.StopClipboardMonitoring();
            isClipboardMonitorActive = false;
            cm.ClipboardChanged -= Cm_ClipboardChanged;
        }

        public void StartNativeMessagingHost()
        {
            nativeMessaging = BrowserMonitor.RunNativeHostHandler(this);
            BrowserMonitor.RunHttpIpcHandler(this);
        }

        public void SubmitDownload(object downloadInfo,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            bool enableSpeedLimit,
            int speedLimit,
            string? queueId,
            bool convertToMp3)
        {

            switch (downloadInfo)
            {
                case SingleSourceHTTPDownloadInfo info:
                    this.StartDownload(
                        info,
                        fileName,
                        fileNameFetchMode,
                        targetFolder,
                        startImmediately,
                        authentication, proxyInfo ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId, convertToMp3
                    );
                    break;
                case DualSourceHTTPDownloadInfo info:
                    this.StartDownload(
                        info,
                        fileName,
                        fileNameFetchMode,
                        targetFolder,
                        startImmediately,
                        authentication, proxyInfo ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                    );
                    break;
                case MultiSourceHLSDownloadInfo info:
                    this.StartDownload(
                        info,
                        fileName,
                        fileNameFetchMode,
                        targetFolder,
                        startImmediately,
                        authentication, proxyInfo ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                    );
                    break;
                case MultiSourceDASHDownloadInfo info:
                    this.StartDownload(
                        info,
                        fileName,
                        fileNameFetchMode,
                        targetFolder,
                        startImmediately,
                        authentication, proxyInfo ?? Config.Instance.Proxy,
                        enableSpeedLimit ? speedLimit : 0, queueId
                        );
                    break;
            }
        }

        public string StartDownload(SingleSourceHTTPDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit,
            string? queueId,
            bool convertToMp3)
        {
            Log.Debug($"Starting download: {fileName} {fileNameFetchMode} {convertToMp3}");
            var http = new SingleSourceHTTPDownloader(info, authentication: authentication,
                proxy: proxyInfo, speedLimit: maxSpeedLimit, mediaProcessor: new FFmpegMediaProcessor(),
                convertToMp3: convertToMp3);
            if (!string.IsNullOrEmpty(queueId))
            {
                QueueManager.AddDownloadsToQueue(queueId!, new string[] { http.Id });
            }
            Helpers.SaveDownloadInfo(http.Id, info);
            http.SetFileName(Helpers.SanitizeFileName(fileName), fileNameFetchMode);
            http.SetTargetDirectory(targetFolder);
            StartDownload(http, startImmediately, authentication, proxyInfo, maxSpeedLimit);
            return http.Id;
        }

        public string StartDownload(DualSourceHTTPDownloadInfo info,
           string fileName,
           FileNameFetchMode fileNameFetchMode,
           string? targetFolder,
           bool startImmediately,
           AuthenticationInfo? authentication,
           ProxyInfo? proxyInfo,
           int maxSpeedLimit,
           string? queueId)
        {
            var http = new DualSourceHTTPDownloader(info, mediaProcessor: new FFmpegMediaProcessor(),
                authentication: authentication, proxy: proxyInfo, speedLimit: maxSpeedLimit);
            if (!string.IsNullOrEmpty(queueId))
            {
                QueueManager.AddDownloadsToQueue(queueId!, new string[] { http.Id });
            }
            Helpers.SaveDownloadInfo(http.Id, info);
            http.SetFileName(Helpers.SanitizeFileName(fileName), fileNameFetchMode);
            http.SetTargetDirectory(targetFolder);
            StartDownload(http, startImmediately, authentication, proxyInfo, maxSpeedLimit);
            return http.Id;
        }

        public string StartDownload(MultiSourceHLSDownloadInfo info,
           string fileName,
           FileNameFetchMode fileNameFetchMode,
           string? targetFolder,
           bool startImmediately,
           AuthenticationInfo? authentication,
           ProxyInfo? proxyInfo,
           int maxSpeedLimit,
           string? queueId)
        {
            var http = new MultiSourceHLSDownloader(info, mediaProcessor: new FFmpegMediaProcessor(),
                authentication: authentication, proxy: proxyInfo, speedLimit: maxSpeedLimit);
            if (!string.IsNullOrEmpty(queueId))
            {
                QueueManager.AddDownloadsToQueue(queueId!, new string[] { http.Id });
            }
            Helpers.SaveDownloadInfo(http.Id, info);
            http.SetFileName(Helpers.SanitizeFileName(fileName), fileNameFetchMode);
            http.SetTargetDirectory(targetFolder);
            StartDownload(http, startImmediately, authentication, proxyInfo, maxSpeedLimit);
            return http.Id;
        }

        public string StartDownload(MultiSourceDASHDownloadInfo info,
           string fileName,
           FileNameFetchMode fileNameFetchMode,
           string targetFolder,
           bool startImmediately,
           AuthenticationInfo? authentication,
           ProxyInfo? proxyInfo,
           int maxSpeedLimit,
           string? queueId)
        {
            var http = new MultiSourceDASHDownloader(info, mediaProcessor: new FFmpegMediaProcessor(),
                authentication: authentication, proxy: proxyInfo, speedLimit: maxSpeedLimit);
            if (!string.IsNullOrEmpty(queueId))
            {
                QueueManager.AddDownloadsToQueue(queueId!, new string[] { http.Id });
            }
            Helpers.SaveDownloadInfo(http.Id, info);
            http.SetFileName(Helpers.SanitizeFileName(fileName), fileNameFetchMode);
            http.SetTargetDirectory(targetFolder);
            StartDownload(http, startImmediately, authentication, proxyInfo, maxSpeedLimit);
            return http.Id;
        }

        private void StartDownload(IBaseDownloader download,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit)
        {
            if (!awakePingTimer.Enabled)
            {
                Log.Debug("Starting keep awaik timer");
                awakePingTimer.Start();
            }
            var id = download.Id;
            var startType = DownloadStartType.Waiting;

            if (!startImmediately)
            {
                startType = DownloadStartType.Stopped;
            }
            else if (liveDownloads.Count >= Config.Instance.MaxParallelDownloads)
            {
                startImmediately = false;
                queuedDownloads.Add(id, false);
            }

            AppUI.AddItemToTop(id, download.TargetFileName, DateTime.Now,
                download.FileSize, download.Type, download.FileNameFetchMode,
                download.PrimaryUrl?.ToString(), startType, authentication,
                proxyInfo, maxSpeedLimit);

            if (startImmediately)
            {
                this.liveDownloads.Add(download.Id, (Downloader: download, NonInteractive: false));
                download.Started += HandleDownloadStart;
                download.Probed += HandleProbeResult;
                download.Finished += DownloadFinished;
                download.ProgressChanged += DownloadProgressChanged;
                download.AssembingProgressChanged += AssembleProgressChanged;
                download.Cancelled += DownloadCancelled;
                download.Failed += DownloadFailed;

                var showProgress = Config.Instance.ShowProgressWindow;
                if (showProgress)
                {
                    var prgWin = CreateProgressWindow(download);
                    activeProgressWindows[download.Id] = prgWin;
                    prgWin.FileNameText = download.TargetFileName;
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} ...";
                    prgWin.UrlText = download.PrimaryUrl?.ToString() ?? string.Empty;
                    prgWin.ShowProgressWindow();
                }

                download.Start();
            }
            else
            {
                download.SaveForLater();
            }
        }

        public void AddBatchLinks(List<Message> messages)
        {
            var list = new List<object>(messages.Count);
            foreach (var message in messages)
            {
                var url = message.Url;
                if (string.IsNullOrEmpty(url)) continue;
                var file = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url)));
                var si = new SingleSourceHTTPDownloadInfo
                {
                    Uri = url,
                    File = file,
                    Headers = message?.RequestHeaders,
                    Cookies = message?.Cookies
                };
                list.Add(si);
            }
            AppUI.ShowDownloadSelectionWindow(FileNameFetchMode.FileNameAndExtension, list);
        }

        public void AddDownload(Message message)
        {
            if (refreshLinkCandidate != null && IsMatchingSingleSourceLink(message))
            {
                HandleSingleSourceLinkRefresh(message);
                return;
            }
            if (Config.Instance.StartDownloadAutomatically)
            {
                var url = message.Url;
                var file = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url)));
                StartDownload(
                    new SingleSourceHTTPDownloadInfo
                    {
                        Uri = url,
                        File = file,
                        Headers = message?.RequestHeaders,
                        Cookies = message?.Cookies
                    },
                    file,
                    FileNameFetchMode.FileNameAndExtension,
                    null,
                    true,
                    null,
                    Config.Instance.Proxy,
                    GetSpeedLimit(), null, false);
            }
            else
            {
                Log.Debug("Adding download");
                AppUI.ShowNewDownloadDialog(message);
            }
            //appUI.InvokeForm(new Action(() =>
            //{
            //    NewDownloadDialog.CreateAndShowDialog(this, appUI.CreateNewDownloadDialog(), message);
            //}));
        }

        public void AddVideoDownload(string videoId)
        {
            var name = string.Empty;
            var size = 0L;
            var contentType = string.Empty;
            var valid = false;
            if (ytVideoList.ContainsKey(videoId))
            {
                if (refreshLinkCandidate != null &&
                    IsMatchingDualSourceLink(ytVideoList[videoId].Info))
                {
                    HandleDualSourceLinkRefresh(ytVideoList[videoId].Info);
                    return;
                }
                name = ytVideoList[videoId].Info.File;
                size = ytVideoList[videoId].DisplayInfo.Size;
                contentType = ytVideoList[videoId].Info.ContentType1;
                valid = true;
            }
            else if (videoList.ContainsKey(videoId))
            {
                if (refreshLinkCandidate != null && IsMatchingSingleSourceLink(videoList[videoId].Info))
                {
                    HandleSingleSourceLinkRefresh(videoList[videoId].Info);
                    return;
                }
                name = videoList[videoId].Info.File;
                size = videoList[videoId].DisplayInfo.Size;
                contentType = videoList[videoId].Info.ContentType;
                valid = true;
            }
            else if (hlsVideoList.ContainsKey(videoId))
            {
                Log.Debug("Download HLS video added with id: " + videoId);
                name = hlsVideoList[videoId].Info.File;
                valid = true;
                try
                {
                    contentType = hlsVideoList[videoId].Info.ContentType;
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                }
            }
            else if (dashVideoList.ContainsKey(videoId))
            {
                Log.Debug("Download DASH video added with id: " + videoId);
                name = dashVideoList[videoId].Info.File;
                contentType = dashVideoList[videoId].Info.ContentType;
                valid = true;
            }
            if (valid)
            {
                if (Config.Instance.StartDownloadAutomatically)
                {
                    StartVideoDownload(
                        videoId, Helpers.SanitizeFileName(name),
                        null, true, null, Config.Instance.Proxy,
                    GetSpeedLimit(), null);
                }
                else
                {
                    AppUI.ShowVideoDownloadDialog(videoId, name, size, contentType);
                }
            }
        }

        public void AddVideoNotifications(IEnumerable<(DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    ytVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotifications(IEnumerable<(SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    videoList.Add(Guid.NewGuid().ToString(), info);
                }
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotifications(IEnumerable<(MultiSourceHLSDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    hlsVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotifications(IEnumerable<(MultiSourceDASHDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
        {
            lock (this)
            {
                foreach (var info in notifications)
                {
                    dashVideoList.Add(Guid.NewGuid().ToString(), info);
                }
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, DualSourceHTTPDownloadInfo info)
        {
            lock (this)
            {
                ytVideoList.Add(Guid.NewGuid().ToString(), (info, displayInfo));
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, SingleSourceHTTPDownloadInfo info)
        {
            lock (this)
            {
                videoList.Add(Guid.NewGuid().ToString(), (info, displayInfo));
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceHLSDownloadInfo info)
        {
            lock (this)
            {
                var id = Guid.NewGuid().ToString();
                Log.Debug("HLS video added with id: " + id);
                hlsVideoList.Add(id, (info, displayInfo));
                nativeMessaging.BroadcastConfig();
            }
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceDASHDownloadInfo info)
        {
            lock (this)
            {
                var id = Guid.NewGuid().ToString();
                Log.Debug("DASH video added with id: " + id);
                dashVideoList.Add(id, (info, displayInfo));
                nativeMessaging.BroadcastConfig();
            }
        }

        //public void DeleteDownloads(List<string> list)
        //{
        //    if (list.Count > 0)
        //    {
        //        if (AppUI.Confirm(null, $"Delete {list.Count} item{(list.Count > 1 ? "s" : "")}?"))
        //        {
        //            var itemsToDelete = new List<RowItem>();
        //            list.ForEach(id =>
        //            {
        //                (var http, var nonInteractive) = liveDownloads.GetValueOrDefault(id);
        //                if (http != null)
        //                {
        //                    http.Stop();
        //                    liveDownloads.Remove(id);
        //                    if (activeProgressWindows.ContainsKey(id))
        //                    {
        //                        activeProgressWindows[id].Destroy();
        //                        activeProgressWindows.Remove(id);
        //                    }
        //                }
        //                else
        //                {
        //                    if (queuedDownloads.ContainsKey(id))
        //                    {
        //                        queuedDownloads.Remove(id);
        //                    }
        //                }
        //            });
        //        }
        //    }
        //}

        public List<(string ID, string File, string DisplayName, DateTime Time)> GetVideoList(bool encode = true)
        {
            lock (this)
            {
                var list = new List<(string ID, string File, string DisplayName, DateTime Time)>();
                foreach (var e in ytVideoList)
                {
                    list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
                }
                foreach (var e in videoList)
                {
                    list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
                }
                foreach (var e in hlsVideoList)
                {
                    list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
                }
                foreach (var e in dashVideoList)
                {
                    list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality, e.Value.DisplayInfo.CreationTime));
                }
                list.Sort((a, b) => a.Time.CompareTo(b.Time));
                return list;
            }
        }

        public void ResumeNonInteractiveDownloads(IEnumerable<string> idList)
        {
            foreach (var id in idList)
            {
                var entry = AppDB.Instance.Downloads.GetDownloadById(id);// AppUI.GetInProgressDownloadEntry(id);
                if (entry != null)
                {
                    ResumeDownload(new Dictionary<string, BaseDownloadEntry> { [id] = entry }, true);
                }
            }
        }

        public void ResumeDownload(Dictionary<string, BaseDownloadEntry> list,
            bool nonInteractive = false)
        {
            if (!awakePingTimer.Enabled)
            {
                Log.Debug("Starting keep awake timer");
                awakePingTimer.Start();
            }

            foreach (var item in list)
            {
                if (liveDownloads.ContainsKey(item.Key) || queuedDownloads.ContainsKey(item.Key)) return;
                if (liveDownloads.Count >= Config.Instance.MaxParallelDownloads)
                {
                    queuedDownloads.Add(item.Key, nonInteractive);
                    AppUI.RunOnUiThread(() =>
                    {
                        AppUI.SetDownloadStatusWaiting(item.Key);
                        Log.Debug("Setting status waiting...");
                    });
                    continue;
                }
                IBaseDownloader download = null;
                switch (item.Value.DownloadType)
                {
                    case "Http":
                        download = new SingleSourceHTTPDownloader((string)item.Key,
                             mediaProcessor: new FFmpegMediaProcessor());
                        break;
                    case "Dash":
                        download = new DualSourceHTTPDownloader((string)item.Key,
                            mediaProcessor: new FFmpegMediaProcessor());
                        break;
                    case "Hls":
                        download = new MultiSourceHLSDownloader(item.Key,
                            mediaProcessor: new FFmpegMediaProcessor());
                        break;
                    case "Mpd-Dash":
                        download = new MultiSourceDASHDownloader(item.Key,
                            mediaProcessor: new FFmpegMediaProcessor());
                        break;
                }
                download.Started += HandleDownloadStart;
                download.Probed += HandleProbeResult;
                download.Finished += DownloadFinished;
                download.ProgressChanged += DownloadProgressChanged;
                download.AssembingProgressChanged += AssembleProgressChanged;
                download.Cancelled += DownloadCancelled;
                download.Failed += DownloadFailed;
                download.SetTargetDirectory(item.Value.TargetDir);
                download.SetFileName(item.Value.Name, item.Value.FileNameFetchMode);
                liveDownloads[item.Key] = (Downloader: download, NonInteractive: nonInteractive);

                var showProgressWindow = Config.Instance.ShowProgressWindow;
                if (showProgressWindow && !nonInteractive)
                {
                    var prgWin = GetProgressWindow(download);// CreateOrGetProgressWindow(download);
                    AppUI.RunOnUiThread(() =>
                    {
                        if (prgWin == null)
                        {
                            prgWin = CreateProgressWindow(download);
                            activeProgressWindows[download.Id] = prgWin;
                        }
                        prgWin.FileNameText = download.TargetFileName;
                        prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} ...";
                        prgWin.DownloadStarted();
                        prgWin.ShowProgressWindow();
                    });
                }
                liveDownloads[item.Key].Downloader.Resume();
            }
        }

        public void ShowProgressWindow(string downloadId)
        {
            try
            {

                if (!liveDownloads.ContainsKey(downloadId))
                {
                    return;
                }
                var downloader = liveDownloads[downloadId].Downloader;
                var prgWin = CreateOrGetProgressWindow(downloader);
                prgWin.FileNameText = downloader.TargetFileName;
                prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} ...";
                prgWin.ShowProgressWindow();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error showing progress window");
            }
        }

        public void SaveState()
        {
        }

        public void SetUI(IListUI listUI)
        {
        }

        public void StartVideoDownload(string videoId,
            string name,
            string? folder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit,
            string? queueId,
            bool convertToMp3 = false //only applicable for dual source http downloads
            )
        {
            //IBaseDownloader downloader = null;
            if (ytVideoList.ContainsKey(videoId))
            {
                StartDownload(ytVideoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                        folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
                //if (convertToMp3)
                //{
                //    var info = new SingleSourceHTTPDownloadInfo
                //    {
                //        Uri = ytVideoList[videoId].Info.Uri2,
                //        Headers = ytVideoList[videoId].Info.Headers2,
                //        Cookies = ytVideoList[videoId].Info.Cookies2,
                //        ContentLength = ytVideoList[videoId].Info.ContentLength2,
                //        File = name
                //    };
                //    StartDownload(info, name, FileNameFetchMode.None,
                //        folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
                //}
                //else
                //{
                //    StartDownload(ytVideoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                //        folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
                //}
                //downloader =
                //    new DualSourceHTTPDownloader(ytVideoList[videoId].Info,
                //    mediaProcessor: new FFmpegMediaProcessor());
            }
            else if (videoList.ContainsKey(videoId))
            {
                StartDownload(videoList[videoId].Info, name, convertToMp3 ? FileNameFetchMode.None : FileNameFetchMode.ExtensionOnly,
                    folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId, convertToMp3);
                //downloader = new SingleSourceHTTPDownloader(videoList[videoId].Info);
            }
            else if (hlsVideoList.ContainsKey(videoId))
            {
                StartDownload(hlsVideoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                    folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
                //Log.Debug("Download HLS video added with id: " + videoId);
                //downloader = new MultiSourceHLSDownloader(hlsVideoList[videoId].Info,
                //    mediaProcessor: new FFmpegMediaProcessor());
            }
            else if (dashVideoList.ContainsKey(videoId))
            {
                StartDownload(dashVideoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                    folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);

                //Log.Debug("Download DASH video added with id: " + videoId);
                //downloader = new MultiSourceDASHDownloader(dashVideoList[videoId].Info,
                //            mediaProcessor: new FFmpegMediaProcessor());
            }
            //if (downloader != null)
            //{
            //    downloader.SetFileName(name, FileNameFetchMode.ExtensionOnly);
            //    downloader.SetTargetDirectory(folder);
            //    StartDownload(downloader, startImmediately);
            //}
        }

        public void StopDownloads(IEnumerable<string> list, bool closeProgressWindow = false)
        {
            var ids = new List<string>(list);
            foreach (var id in ids)
            {
                (var http, _) = liveDownloads.GetValueOrDefault(id);
                if (http != null)
                {
                    http.Stop();
                    liveDownloads.Remove(id);
                }
                else
                {
                    if (queuedDownloads.ContainsKey(id))
                    {
                        queuedDownloads.Remove(id);
                    }
                }

                if (activeProgressWindows.ContainsKey(id) && closeProgressWindow)
                {
                    var prgWin = activeProgressWindows[id];
                    activeProgressWindows.Remove(id);
                    prgWin.DestroyWindow();
                    Log.Debug("Progress window removed");
                }
            };
        }

        void DownloadProgressChanged(object source, ProgressResultEventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                AppUI.UpdateProgress(http.Id, args.Progress, args.DownloadSpeed, args.Eta);
                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadProgress = args.Progress;
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} {Helpers.FormatSize(args.Downloaded)} / {Helpers.FormatSize(http.FileSize)}";
                    prgWin.DownloadSpeedText = Helpers.FormatSize((long)args.DownloadSpeed) + "/s";
                    prgWin.DownloadETAText = $"{TextResource.GetText("MSG_TIME_LEFT")}: {Helpers.ToHMS(args.Eta)}";
                }
            }
        }

        void AssembleProgressChanged(object source, ProgressResultEventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                //AppUI.UpdateProgress(http.Id, args.Progress, args.DownloadSpeed, args.Eta);
                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadProgress = args.Progress;
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_ASSEMBLING")} {Helpers.FormatSize(args.Downloaded)} / {Helpers.FormatSize(http.FileSize)}";
                    prgWin.DownloadSpeedText = "---";
                    prgWin.DownloadETAText = "---";
                }
            }
        }

        void DownloadFinished(object source, EventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                DetachEventHandlers(http);
                AppUI.DownloadFinished(http.Id, http.FileSize < 0 ? new FileInfo(http.TargetFile).Length : http.FileSize, http.TargetFile);

                var showCompleteDialog = false;
                if (liveDownloads.ContainsKey(http.Id))
                {
                    (_, bool nonInteractive) = liveDownloads[http.Id];
                    liveDownloads.Remove(http.Id);

                    if (!nonInteractive && Config.Instance.ShowDownloadCompleteWindow)
                    {
                        showCompleteDialog = true;
                    }
                }

                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    activeProgressWindows.Remove(http.Id);
                    prgWin.DownloadId = null;
                    prgWin.DestroyWindow();
                }

                if (showCompleteDialog)
                {
                    AppUI.ShowDownloadCompleteDialog(http.TargetFileName, Path.GetDirectoryName(http.TargetFile));
                }

                if (Config.Instance.ScanWithAntiVirus)
                {
                    Helpers.RunAntivirus(Config.Instance.AntiVirusExecutable, Config.Instance.AntiVirusArgs, http.TargetFile);
                }

                Helpers.RunGC();

                ProcessNextQueuedItem();
            }
        }

        void DownloadFailed(object source, DownloadFailedEventArgs args)
        {
            lock (this)
            {
                Log.Debug("Download failed: " + args.ErrorCode);
                var http = source as IBaseDownloader;
                DetachEventHandlers(http);
                liveDownloads.Remove(http.Id);
                AppUI.DownloadFailed(http.Id);
                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadFailed(new ErrorDetails { Message = ErrorMessages.GetLocalizedErrorMessage(args.ErrorCode) });
                    //prgWin.DownloadETAText = "Download Failed";
                    //activeProgressWindows.Remove(http.Id);
                    //appUI.ShowMessageBox("Download failed");
                    //prgWin.Destroy();
                }

                Helpers.RunGC();
                ProcessNextQueuedItem();
            }
        }

        void DownloadCancelled(object source, EventArgs args)
        {
            lock (this)
            {
                Log.Debug("Download cancelled");
                var http = source as IBaseDownloader;
                DetachEventHandlers(http);
                liveDownloads.Remove(http.Id);
                AppUI.DownloadCanelled(http.Id);

                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadCancelled();
                    //var prgWin = activeProgressWindows[http.Id];
                    //activeProgressWindows.Remove(http.Id);
                    //prgWin.Destroy();
                }

                Helpers.RunGC();
                ProcessNextQueuedItem();
            }
        }

        void HandleProbeResult(object source, EventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                AppUI.UpdateItem(http.Id, http.TargetFileName, http.FileSize > 0 ? http.FileSize : 0);
                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.FileNameText = http.TargetFileName;
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} {Helpers.FormatSize(0)} / {Helpers.FormatSize(http.FileSize)}";
                }
            }
        }

        void HandleDownloadStart(object source, EventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                AppUI.DownloadStarted(http.Id);
            }
        }

        //public void LoadDownloadList()
        //{
        //    //var inprogresDownloadListFile = Path.Combine(
        //    //            Config.DataDir,
        //    //            "incomplete-downloads.json");
        //    //if (File.Exists(inprogresDownloadListFile))
        //    //{
        //    //    AppUI.SetInProgressDownloadList(JsonConvert.DeserializeObject<List<InProgresDownloadEntry>>(
        //    //        File.ReadAllText(inprogresDownloadListFile)));
        //    //}
        //    ////var finishedDownloadListFile = Path.Combine(
        //    ////            Config.DataDir,
        //    ////            "finished-downloads.json");
        //    ////if (File.Exists(finishedDownloadListFile))
        //    ////{
        //    ////    AppUI.SetFinishedDownloadList(JsonConvert.DeserializeObject<List<FinishedDownloadEntry>>(
        //    ////        File.ReadAllText(finishedDownloadListFile)));
        //    ////}

        //    //AppUI.LoadDownloadsDB();
        //}

        //public void SaveInProgressList(IEnumerable<InProgresDownloadEntry> list)
        //{
        //    lock (this)
        //    {
        //        File.WriteAllText(Path.Combine(Config.DataDir, "incomplete-downloads.json"), JsonConvert.SerializeObject(list));
        //    }
        //}

        //public void SaveFinishedList(IEnumerable<FinishedDownloadEntry> list)
        //{
        //    //lock (this)
        //    //{
        //    //    File.WriteAllText(Path.Combine(Config.DataDir, "finished-downloads.json"), JsonConvert.SerializeObject(list));
        //    //}
        //}

        private string EncodeToCharCode(string text)
        {
            var sb = new StringBuilder();
            int count = 0;
            foreach (char ch in text)
            {
                if (count > 0)
                    sb.Append(",");
                sb.Append((int)ch);
                count++;
            }
            return sb.ToString();
        }

        private IProgressWindow? GetProgressWindow(IBaseDownloader downloader)
        {
            IProgressWindow? prgWin = null;
#pragma warning disable CS8604 // Possible null reference argument.
            if (activeProgressWindows.ContainsKey(downloader.Id))
#pragma warning restore CS8604 // Possible null reference argument.
            {
                prgWin = activeProgressWindows[downloader.Id];
            }
            return prgWin;
        }

        private IProgressWindow CreateOrGetProgressWindow(IBaseDownloader downloader)
        {
            IProgressWindow prgWin = null;
            if (activeProgressWindows.ContainsKey(downloader.Id))
            {
                prgWin = activeProgressWindows[downloader.Id];
            }
            else
            {
                prgWin = CreateProgressWindow(downloader);
                //prgWin.UrlText = AppUI.GetInProgressDownloadEntry(downloader.Id)?.PrimaryUrl;
                activeProgressWindows[downloader.Id] = prgWin;
            }
            //var prgWin = activeProgressWindows.ContainsKey(item.Key) ? activeProgressWindows[item.Key]
            //    : appUI.CreateProgressWindow(item.Key);
            //prgWin.FileNameText = downloader.TargetFileName;
            //prgWin.FileSizeText = $"Downloading {Helpers.FormatSize(0)} / {Helpers.FormatSize(downloader.FileSize)}";
            return prgWin;
        }

        private IProgressWindow CreateProgressWindow(IBaseDownloader downloader)
        {
            var prgWin = AppUI.CreateProgressWindow(downloader.Id);
            prgWin.UrlText = AppUI.GetInProgressDownloadEntry(downloader.Id)?.PrimaryUrl;
            prgWin.DownloadSpeedText = "---";
            prgWin.DownloadETAText = "---";
            prgWin.FileSizeText = "---";
            return prgWin;
        }

        public void ClearVideoList()
        {
            ytVideoList.Clear();
            hlsVideoList.Clear();
            dashVideoList.Clear();
            videoList.Clear();
            nativeMessaging.BroadcastConfig();
        }

        public bool IsDownloadActive(string id)
        {
            return liveDownloads.ContainsKey(id) || queuedDownloads.ContainsKey(id);
        }

        private void ProcessNextQueuedItem()
        {
            if (queuedDownloads.Count > 0)
            {
                var kv = queuedDownloads.First();
                queuedDownloads.Remove(kv.Key);
                var entry = AppDB.Instance.Downloads.GetDownloadById(kv.Key);// AppUI.GetInProgressDownloadEntry(kv.Key);
                if (entry != null)
                {
                    ResumeDownload(new Dictionary<string, BaseDownloadEntry> { [kv.Key] = entry }, kv.Value);
                }
            }
            else
            {
                if (Config.Instance.ShutdownAfterAllFinished)
                {
                    Helpers.ShutDownPC();
                }
                if (awakePingTimer.Enabled)
                {
                    Log.Debug("Stopping keep awake timer");
                    awakePingTimer.Stop();
                }
                if (Config.Instance.RunCommandAfterCompletion)
                {
                    Helpers.RunCommand(Config.Instance.AfterCompletionCommand);
                }
            }
        }

        public void StartScheduler()
        {
            this.scheduler = new Scheduler(this);
            this.scheduler.Start();
        }

        public void RenameDownload(string id, string folder, string file)
        {
            if (liveDownloads.ContainsKey(id))
            {
                var downloader = liveDownloads[id].Downloader;
                downloader.SetTargetDirectory(folder);
                downloader.SetFileName(file, downloader.FileNameFetchMode);
            }
            AppUI.RenameFileOnUI(id, folder, file);
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

        private bool IsMatchingDualSourceLink(DualSourceHTTPDownloadInfo info)
        {
            if (!(refreshLinkCandidate is DualSourceHTTPDownloader)) return false;
            return info.ContentLength > 0 &&
                ((DualSourceHTTPDownloader)refreshLinkCandidate).FileSize == info.ContentLength;
        }

        private void HandleSingleSourceLinkRefresh(SingleSourceHTTPDownloadInfo info)
        {
            ((SingleSourceHTTPDownloader)refreshLinkCandidate).SetDownloadInfo(info);
            refreshLinkCandidate = null;
            RefreshedLinkReceived?.Invoke(this, EventArgs.Empty);
            ClearRefreshRecivedEvents();
        }

        private void HandleDualSourceLinkRefresh(DualSourceHTTPDownloadInfo info)
        {
            ((DualSourceHTTPDownloader)refreshLinkCandidate).SetDownloadInfo(info);
            refreshLinkCandidate = null;
            RefreshedLinkReceived?.Invoke(this, EventArgs.Empty);
            ClearRefreshRecivedEvents();
        }

        private void ClearRefreshRecivedEvents()
        {
            foreach (Delegate d in RefreshedLinkReceived?.GetInvocationList())
            {
                RefreshedLinkReceived -= (EventHandler)d;
            }
        }

        public void WaitFromRefreshedLink(HTTPDownloaderBase downloader)
        {
            this.refreshLinkCandidate = downloader;
        }

        public void ClearRefreshLinkCandidate()
        {
            this.refreshLinkCandidate = null;
        }

        private void DetachEventHandlers(IBaseDownloader download)
        {
            try
            {
                download.Started -= HandleDownloadStart;
                download.Probed -= HandleProbeResult;
                download.Finished -= DownloadFinished;
                download.ProgressChanged -= DownloadProgressChanged;
                download.AssembingProgressChanged += AssembleProgressChanged;
                download.Cancelled -= DownloadCancelled;
                download.Failed -= DownloadFailed;
            }
            catch { }
        }

        public void ApplyConfig()
        {
            if (Config.Instance.MonitorClipboard)
            {
                StartClipboardMonitor();
            }
            else
            {
                StopClipboardMonitor();
            }
            nativeMessaging.BroadcastConfig();
        }

        private void Cm_ClipboardChanged(object? sender, EventArgs e)
        {
            var text = AppUI.GetClipboardMonitor().GetClipboardText();
            if (!string.IsNullOrEmpty(text) && Helpers.IsUriValid(text) && text != lastClipboardText)
            {
                lastClipboardText = text;
                AddDownload(new Message { Url = text });
            }
        }

        public AuthenticationInfo? PromptForCredential(string id, string message)
        {
            try
            {
                if (liveDownloads[id].NonInteractive)
                {
                    return null;
                }
                return AppUI.PromtForCredentials(message);
            }
            catch { }
            return null;
        }

        public void HideProgressWindow(string id)
        {
            if (activeProgressWindows.ContainsKey(id))
            {
                var prgWin = activeProgressWindows[id];
                activeProgressWindows.Remove(id);
                prgWin.DestroyWindow();
            }
        }

        public string? GetPrimaryUrl(BaseDownloadEntry entry)
        {
            if (entry == null) return null;
            switch (entry.DownloadType)
            {
                case "Http":
                    var h1 = Helpers.LoadSingleSourceHTTPDownloadInfo(entry.Id);// LoadInfo<SingleSourceHTTPDownloadInfo>(entry.Id);
                    if (h1 != null)
                    {
                        return h1.Uri;
                    }
                    break;
                case "Dash":
                    var h2 = Helpers.LoadDualSourceHTTPDownloadInfo(entry.Id);// LoadInfo<DualSourceHTTPDownloadInfo>(entry.Id);
                    if (h2 != null)
                    {
                        return h2.Uri1;
                    }
                    break;
                case "Hls":
                    var hls = Helpers.LoadMultiSourceHLSDownloadInfo(entry.Id);// LoadInfo<MultiSourceHLSDownloadInfo>(entry.Id);
                    if (hls != null)
                    {
                        return hls.VideoUri;
                    }
                    break;
                case "Mpd-Dash":
                    var dash = Helpers.LoadMultiSourceDASHDownloadInfo(entry.Id); //LoadInfo<MultiSourceDASHDownloadInfo>(entry.Id);
                    if (dash != null)
                    {
                        return dash.Url;
                    }
                    break;
            }

            return null;
        }

        public void RemoveDownload(BaseDownloadEntry entry, bool deleteDownloadedFile)
        {
            try
            {
                if (entry == null) return;
                string? tempDir = null;
                var validEntry = false;
                switch (entry.DownloadType)
                {
                    case "Http":
                        var h1 = DownloadStateStore.LoadSingleSourceHTTPDownloaderState(entry.Id);
                        if (h1 != null)
                        {
                            tempDir = h1.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Dash":
                        var h2 = DownloadStateStore.LoadDualSourceHTTPDownloaderState(entry.Id);
                        if (h2 != null)
                        {
                            tempDir = h2.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Hls":
                        var hls = DownloadStateStore.LoadMultiSourceHLSDownloadState(entry.Id);
                        if (hls != null)
                        {
                            tempDir = hls.TempDirectory;
                            validEntry = true;
                        }
                        break;
                    case "Mpd-Dash":
                        var dash = DownloadStateStore.LoadMultiSourceDASHDownloadState(entry.Id);
                        if (dash != null)
                        {
                            tempDir = dash.TempDirectory;
                            validEntry = true;
                        }
                        break;
                }

                if (validEntry)
                {
                    var infoFile = Path.Combine(Config.DataDir, entry.Id + ".info");
                    var stateFile = Path.Combine(Config.DataDir, entry.Id + ".state");
                    if (Directory.Exists(tempDir) && !string.IsNullOrEmpty(tempDir))
                    {
                        Directory.Delete(tempDir, true);
                    }
                    if (File.Exists(stateFile))
                    {
                        File.Delete(stateFile);
                    }
                    if (File.Exists(infoFile))
                    {
                        File.Delete(infoFile);
                    }

                    if (entry is FinishedDownloadEntry && deleteDownloadedFile)
                    {
                        var outFile = Path.Combine(entry.TargetDir, entry.Name);
                        if (File.Exists(outFile))
                        {
                            File.Delete(outFile);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error deleting temp folder");
            }
        }

        public void RestartDownload(BaseDownloadEntry entry)
        {
            if (entry == null) return;
            var validEntry = true;
            try
            {
                switch (entry.DownloadType)
                {
                    case "Http":
                        var h1 = Helpers.LoadSingleSourceHTTPDownloadInfo(entry.Id);
                        if (h1 != null)
                        {
                            this.StartDownload(h1, entry.Name,
                                FileNameFetchMode.FileNameAndExtension,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, GetSpeedLimit(), null,
                                h1.ConvertToMp3);
                        }
                        break;
                    case "Dash":
                        var h2 = Helpers.LoadDualSourceHTTPDownloadInfo(entry.Id);
                        if (h2 != null)
                        {
                            this.StartDownload(h2, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, GetSpeedLimit(), null);
                        }
                        break;
                    case "Hls":
                        var hls = Helpers.LoadMultiSourceHLSDownloadInfo(entry.Id);
                        if (hls != null)
                        {
                            this.StartDownload(hls, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, GetSpeedLimit(), null);
                        }
                        break;
                    case "Mpd-Dash":
                        var dash = Helpers.LoadMultiSourceDASHDownloadInfo(entry.Id);
                        if (dash != null)
                        {
                            this.StartDownload(dash, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, GetSpeedLimit(), null);
                        }
                        break;
                    default:
                        validEntry = false;
                        break;
                }

                if (validEntry)
                {
                    RemoveDownload(entry, false);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error restarting download");
            }
        }

        private int GetSpeedLimit()
        {
            if (Config.Instance.EnableSpeedLimit)
            {
                return Config.Instance.DefaltDownloadSpeed;
            }
            return 0;
        }

        //private T? LoadInfo<T>(string id)
        //{
        //    try
        //    {
        //        return JsonConvert.DeserializeObject<T>(
        //                File.ReadAllText(Path.Combine(Config.DataDir, id + ".info")));
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, "Error");
        //    }
        //    return default(T?);
        //}

        //private T? LoadState<T>(string id)
        //{
        //    try
        //    {
        //        return JsonConvert.DeserializeObject<T>(
        //                File.ReadAllText(Path.Combine(Config.DataDir, id + ".state")));
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Debug(ex, "Error");
        //    }
        //    return default(T?);
        //}

        private void CheckForUpdate()
        {
            try
            {
                Log.Debug("Checking for updates...");
                if (UpdateChecker.GetAppUpdates(AppVerion, out IList<UpdateInfo> updates, out bool firstUpdate))
                {
                    this.Updates = updates;
                    this.ComponentsInstalled = !firstUpdate;
                    AppUI.ShowUpdateAvailableNotification();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "CheckForUpdate");
            }
        }

        private string GetUpdateText()
        {
            if (Updates == null || Updates.Count < 1) return TextResource.GetText("MSG_NO_UPDATE");
            var text = new StringBuilder();
            var size = 0L;
            text.Append((ComponentsInstalled ? "Update available: " : "XDM require FFmpeg and YoutubeDL to download streaming videos") + Environment.NewLine);
            foreach (var update in Updates)
            {
                text.Append(update.Name + " " + update.TagName + Environment.NewLine);
                size += update.Size;
            }
            text.Append(Environment.NewLine + "Total download: " + Helpers.FormatSize(size) + Environment.NewLine);
            text.Append("Would you like to continue?");
            return text.ToString();
        }

        public void Export(string path)
        {
            ImportExport.Export(path);
        }

        public void Import(string path)
        {
            ImportExport.Import(path);
            AppUI.ShowMessageBox(null, TextResource.GetText("MSG_IMPORT_DONE"));
        }

        public bool IsFFmpegRequiredForDownload(string id)
        {
            return ytVideoList.ContainsKey(id) || dashVideoList.ContainsKey(id) || hlsVideoList.ContainsKey(id);
        }

        public void UpdateSpeedLimit(string id, bool enable, int limit)
        {
            if (liveDownloads.TryGetValue(id, out (IBaseDownloader Downloader, bool _) entry))
            {
                entry.Downloader.UpdateSpeedLimit(enable, limit);
            }
        }

        public bool GetLiveDownloadSpeedLimit(string id, out bool enabled, out int limit)
        {
            enabled = false;
            limit = 0;
            if (liveDownloads.TryGetValue(id, out (IBaseDownloader Downloader, bool _) entry))
            {
                enabled = entry.Downloader.EnableSpeedLimit;
                limit = entry.Downloader.SpeedLimit;
                return true;
            }
            return false;
        }
    }
}
