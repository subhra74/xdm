using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Common.UI;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common.Dash;
using XDM.Core.Lib.Common.Hls;
using XDM.Core.Lib.Common.Segmented;
using XDM.Core.Lib.Common.MediaProcessor;
using XDM.Core.Lib.Util;
using BrowserMonitoring;
using XDM.Core.Lib.Common.Collections;
using System.Timers;
using TraceLog;
using Translations;

#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

//using XDM.Core.Lib.Downloader.YT.Dash;

namespace XDMApp
{
    public class XDMApp : IApp
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
        private System.Threading.Timer UpdateCheckTimer;

        public IList<UpdateInfo>? Updates { get; private set; }
        public bool ComponentsInstalled { get; private set; }
        public bool IsAppUpdateAvailable { get => Updates?.Any(u => !u.IsExternal) ?? false; }
        public string ComponentUpdateText { get => GetUpdateText(); }

        public int ActiveDownloadCount { get => liveDownloads.Count + queuedDownloads.Count; }

        public IAppUI AppUI { get; set; }

        public string HelpPage => "https://subhra74.github.io/xdm/redirect-support.html";
        public string UpdatePage => $"https://subhra74.github.io/xdm/update-checker.html?v={AppVerion}";
        public string IssuePage => "https://subhra74.github.io/xdm/redirect-issue.html";
        public string ChromeExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=chrome";
        public string FirefoxExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=firefox";
        public string OperaExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=opera";
        public string EdgeExtensionUrl => "https://subhra74.github.io/xdm/redirect.html?target=edge";

        public string[] Args { get; set; }

        public XDMApp()
        {
            var configPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".xdman");
            Directory.CreateDirectory(configPath);
            Config.DataDir = configPath;
            Config.LoadConfig();

            awakePingTimer = new Timer(60000)
            {
                AutoReset = true
            };
            awakePingTimer.Elapsed += (a, b) => Helpers.SendKeepAlivePing();

            //UpdateCheckTimer = new System.Threading.Timer(
            //    callback: a => CheckForUpdate(),
            //    state: null,
            //    dueTime: TimeSpan.FromSeconds(5),
            //    period: TimeSpan.FromHours(3));

            QueueManager.Load();
        }

        public void StartClipboardMonitor()
        {
            if (isClipboardMonitorActive) return;
            if (Config.Instance.MonitorClipboard && AppUI is IClipboardMonitor cm)
            {
                cm?.StartClipboardMonitoring();
                isClipboardMonitorActive = true;
                cm.ClipboardChanged += Cm_ClipboardChanged;
            }
        }

        public void StopClipboardMonitor()
        {
            if (!isClipboardMonitorActive) return;
            if (AppUI is IClipboardMonitor cm)
            {
                cm?.StopClipboardMonitoring();
                isClipboardMonitorActive = false;
                cm.ClipboardChanged -= Cm_ClipboardChanged;
            }
        }

        public void StartNativeMessagingHost()
        {
            nativeMessaging = BrowserMonitor.RunNativeHostHandler(this);
            BrowserMonitor.RunHttpIpcHandler(this);
        }

        public string StartDownload(SingleSourceHTTPDownloadInfo info,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit,
            string? queueId)
        {
            var http = new SingleSourceHTTPDownloader(info, authentication: authentication,
                proxy: proxyInfo, speedLimit: maxSpeedLimit);
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
                download.Cancelled += DownloadCancelled;
                download.Failed += DownloadFailed;

                var showProgress = Config.Instance.ShowProgressWindow;
                if (showProgress)
                {
                    var prgWin = CreateProgressWindow(download);
                    activeProgressWindows[download.Id] = prgWin;
                    prgWin.FileNameText = download.TargetFileName;
                    prgWin.FileSizeText = string.Empty;
                    activeProgressWindows[download.Id] = prgWin;
                    prgWin.ShowProgressWindow();
                }

                download.Start();
            }
            else
            {
                download.SaveForLater();
            }
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
                    GetSpeedLimit(), null);
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
                valid = true;
            }
            else if (hlsVideoList.ContainsKey(videoId))
            {
                Log.Debug("Download HLS video added with id: " + videoId);
                name = hlsVideoList[videoId].Info.File;
                valid = true;
            }
            else if (dashVideoList.ContainsKey(videoId))
            {
                Log.Debug("Download DASH video added with id: " + videoId);
                name = dashVideoList[videoId].Info.File;
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
                    AppUI.ShowVideoDownloadDialog(videoId, name, size);
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
            ytVideoList.Add(Guid.NewGuid().ToString(), (info, displayInfo));
            nativeMessaging.BroadcastConfig();
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, SingleSourceHTTPDownloadInfo info)
        {
            videoList.Add(Guid.NewGuid().ToString(), (info, displayInfo));
            nativeMessaging.BroadcastConfig();
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceHLSDownloadInfo info)
        {
            var id = Guid.NewGuid().ToString();
            Log.Debug("HLS video added with id: " + id);
            hlsVideoList.Add(id, (info, displayInfo));
            nativeMessaging.BroadcastConfig();
        }

        public void AddVideoNotification(StreamingVideoDisplayInfo displayInfo, MultiSourceDASHDownloadInfo info)
        {
            var id = Guid.NewGuid().ToString();
            Log.Debug("DASH video added with id: " + id);
            dashVideoList.Add(id, (info, displayInfo));
            nativeMessaging.BroadcastConfig();
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

        public List<(string ID, string File, string DisplayName)> GetVideoList(bool encode = true)
        {
            var list = new List<(string ID, string File, string DisplayName)>();
            foreach (var e in ytVideoList)
            {
                list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality));
            }
            foreach (var e in videoList)
            {
                list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality));
            }
            foreach (var e in hlsVideoList)
            {
                list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality));
            }
            foreach (var e in dashVideoList)
            {
                list.Add((e.Key, encode ? EncodeToCharCode(e.Value.Info.File) : e.Value.Info.File, e.Value.DisplayInfo.Quality));
            }
            return list;
        }

        public void ResumeNonInteractiveDownloads(IEnumerable<string> idList)
        {
            foreach (var id in idList)
            {
                AppUI.RunOnUiThread(() =>
                {
                    var entry = AppUI.GetInProgressDownloadEntry(id);
                    if (entry != null)
                    {
                        ResumeDownload(new Dictionary<string, BaseDownloadEntry> { [id] = entry }, true);
                    }
                });
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

            AppUI.RunOnUiThread((Action)(() =>
            {
                foreach (var item in list)
                {
                    if (liveDownloads.ContainsKey(item.Key) || queuedDownloads.ContainsKey(item.Key)) return;
                    if (liveDownloads.Count >= Config.Instance.MaxParallelDownloads)
                    {
                        queuedDownloads.Add(item.Key, nonInteractive);
                        AppUI.SetDownloadStatusWaiting(item.Key);
                        Log.Debug("Setting status waiting...");
                        continue;
                    }
                    IBaseDownloader download = null;
                    switch (item.Value.DownloadType)
                    {
                        case "Http":
                            download = new SingleSourceHTTPDownloader((string)item.Key);
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
                    download.Cancelled += DownloadCancelled;
                    download.Failed += DownloadFailed;
                    download.SetTargetDirectory(item.Value.TargetDir);
                    download.SetFileName(item.Value.Name, item.Value.FileNameFetchMode);
                    liveDownloads[item.Key] = (Downloader: download, NonInteractive: nonInteractive);
                    liveDownloads[item.Key].Downloader.Resume();

                    var showProgressWindow = Config.Instance.ShowProgressWindow;
                    if (showProgressWindow && !nonInteractive)
                    {
                        var prgWin = CreateOrGetProgressWindow(download);
                        prgWin.FileNameText = download.TargetFileName;
                        prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} ...";
                        prgWin.DownloadStarted();
                        prgWin.ShowProgressWindow();
                    }
                }
            }));
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
            string folder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            int maxSpeedLimit,
            string? queueId)
        {
            //IBaseDownloader downloader = null;
            if (ytVideoList.ContainsKey(videoId))
            {
                StartDownload(ytVideoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                    folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
                //downloader =
                //    new DualSourceHTTPDownloader(ytVideoList[videoId].Info,
                //    mediaProcessor: new FFmpegMediaProcessor());
            }
            else if (videoList.ContainsKey(videoId))
            {
                StartDownload(videoList[videoId].Info, name, FileNameFetchMode.ExtensionOnly,
                    folder, startImmediately, authentication, proxyInfo, GetSpeedLimit(), queueId);
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
            AppUI.RunOnUiThread(() =>
            {
                foreach (var id in list)
                {
                    (var http, var nonInteractive) = liveDownloads.GetValueOrDefault(id);
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
                        prgWin.Destroy();
                        Log.Debug("Progress window removed");
                    }
                };
            });
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

        void DownloadFinished(object source, EventArgs args)
        {
            lock (this)
            {
                var http = source as IBaseDownloader;
                DetachEventHandlers(http);
                AppUI.DownloadFinished(http.Id, http.FileSize < 0 ? new FileInfo(http.TargetFile).Length : http.FileSize, http.TargetFile);

                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    activeProgressWindows.Remove(http.Id);
                    prgWin.Destroy();
                }

                (_, bool nonInteractive) = liveDownloads[http.Id];
                liveDownloads.Remove(http.Id);

                if (!nonInteractive && Config.Instance.ShowDownloadCompleteWindow)
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
                prgWin.UrlText = AppUI.GetInProgressDownloadEntry(downloader.Id)?.PrimaryUrl;
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
                AppUI.RunOnUiThread(() =>
                {
                    var entry = AppUI.GetInProgressDownloadEntry(kv.Key);
                    if (entry != null)
                    {
                        ResumeDownload(new Dictionary<string, BaseDownloadEntry> { [kv.Key] = entry }, kv.Value);
                    }
                });
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

        private void Cm_ClipboardChanged(object sender, EventArgs e)
        {
            var text = ((IClipboardMonitor)AppUI).GetClipboardText();
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
                activeProgressWindows.Remove(id);
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
                var stateFile = Path.Combine(Config.DataDir, entry.Id + ".state");
                var bytes = File.ReadAllBytes(stateFile);
                switch (entry.DownloadType)
                {
                    case "Http":
                        var h1 = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(bytes);
                        if (h1 != null)
                        {
                            tempDir = h1.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Dash":
                        var h2 = DownloadStateStore.DualSourceHTTPDownloaderStateFromBytes(bytes);
                        if (h2 != null)
                        {
                            tempDir = h2.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Hls":
                        var hls = DownloadStateStore.MultiSourceHLSDownloadStateFromBytes(bytes);
                        if (hls != null)
                        {
                            tempDir = hls.TempDirectory;
                            validEntry = true;
                        }
                        break;
                    case "Mpd-Dash":
                        var dash = DownloadStateStore.MultiSourceDASHDownloadStateFromBytes(bytes);
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
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, GetSpeedLimit(), null);
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
            if (Updates == null) return string.Empty;
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
