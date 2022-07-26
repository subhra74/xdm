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

namespace XDM.Core
{
    public class ApplicationCore : IApplicationCore
    {
        public Version AppVerion => new(8, 0, 0);
        private Dictionary<string, (IBaseDownloader Downloader, bool NonInteractive)> liveDownloads = new();
        private GenericOrderedDictionary<string, bool> queuedDownloads = new();
        private GenericOrderedDictionary<string, IProgressWindow> activeProgressWindows = new();
        private Scheduler scheduler;
        private bool isClipboardMonitorActive = false;
        private string lastClipboardText;
        private Timer awakePingTimer;
        private System.Threading.Timer UpdateCheckTimer;

        public IList<UpdateInfo>? Updates { get; private set; }
        public bool ComponentsInstalled { get; private set; }
        public bool IsAppUpdateAvailable => Updates?.Any(u => !u.IsExternal) ?? false;
        public bool IsComponentUpdateAvailable => Updates?.Any(u => u.IsExternal) ?? false;
        public string ComponentUpdateText => GetUpdateText();
        public int ActiveDownloadCount { get => liveDownloads.Count + queuedDownloads.Count; }

        public string UpdatePage => $"https://subhra74.github.io/xdm/update-checker.html?v={AppVerion}";

        public string[] Args { get; set; }

        public ApplicationCore()
        {
            ApplicationContext.Initialized += AppInstance_Initialized;
        }

        private void AppInstance_Initialized(object sender, EventArgs e)
        {
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
            var cm = ApplicationContext.Application.GetClipboardMonitor();
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
            var cm = ApplicationContext.Application.GetClipboardMonitor();
            cm.StopClipboardMonitoring();
            isClipboardMonitorActive = false;
            cm.ClipboardChanged -= Cm_ClipboardChanged;
        }

        public void StartNativeMessagingHost()
        {
            BrowserMonitor.RunNativeHostHandler();
            BrowserMonitor.RunHttpIpcHandler();
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

            ApplicationContext.Application.AddItemToTop(id, download.TargetFileName, DateTime.Now,
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
            ApplicationContext.Application.ShowDownloadSelectionWindow(FileNameFetchMode.FileNameAndExtension, list);
        }

        public void AddDownload(Message message)
        {
            if (ApplicationContext.LinkRefresher.LinkAccepted(message)) return;

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
                    Helpers.GetSpeedLimit(), null, false);
            }
            else
            {
                Log.Debug("Adding download");
                ApplicationContext.Application.ShowNewDownloadDialog(message);
            }
        }

        public void ResumeNonInteractiveDownloads(IEnumerable<string> idList)
        {
            foreach (var id in idList)
            {
                var entry = AppDB.Instance.Downloads.GetDownloadById(id);// ApplicationContext.Current.GetInProgressDownloadEntry(id);
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
                    ApplicationContext.Application.RunOnUiThread(() =>
                    {
                        ApplicationContext.Application.SetDownloadStatusWaiting(item.Key);
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
                    ApplicationContext.Application.RunOnUiThread(() =>
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
                ApplicationContext.Application.UpdateProgress(http.Id, args.Progress, args.DownloadSpeed, args.Eta);
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
                ApplicationContext.Application.DownloadFinished(http.Id, http.FileSize < 0 ? new FileInfo(http.TargetFile).Length : http.FileSize, http.TargetFile);

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
                    ApplicationContext.Application.ShowDownloadCompleteDialog(http.TargetFileName, Path.GetDirectoryName(http.TargetFile));
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
                ApplicationContext.Application.DownloadFailed(http.Id);
                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadFailed(new ErrorDetails { Message = ErrorMessages.GetLocalizedErrorMessage(args.ErrorCode) });
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
                ApplicationContext.Application.DownloadCanelled(http.Id);

                if (activeProgressWindows.ContainsKey(http.Id))
                {
                    var prgWin = activeProgressWindows[http.Id];
                    prgWin.DownloadCancelled();
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
                ApplicationContext.Application.UpdateItem(http.Id, http.TargetFileName, http.FileSize > 0 ? http.FileSize : 0);
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
                ApplicationContext.Application.DownloadStarted(http.Id);
            }
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
                activeProgressWindows[downloader.Id] = prgWin;
            }
            return prgWin;
        }

        private IProgressWindow CreateProgressWindow(IBaseDownloader downloader)
        {
            var prgWin = ApplicationContext.Application.CreateProgressWindow(downloader.Id);
            prgWin.UrlText = ApplicationContext.Application.GetInProgressDownloadEntry(downloader.Id)?.PrimaryUrl;
            prgWin.DownloadSpeedText = "---";
            prgWin.DownloadETAText = "---";
            prgWin.FileSizeText = "---";
            return prgWin;
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
                var entry = AppDB.Instance.Downloads.GetDownloadById(kv.Key);// ApplicationContext.Current.GetInProgressDownloadEntry(kv.Key);
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
            this.scheduler = new Scheduler();
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
            ApplicationContext.Application.RenameFileOnUI(id, folder, file);
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
        }

        private void Cm_ClipboardChanged(object? sender, EventArgs e)
        {
            var text = ApplicationContext.Application.GetClipboardMonitor().GetClipboardText();
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
                return ApplicationContext.Application.PromtForCredentials(message);
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
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, Helpers.GetSpeedLimit(), null,
                                h1.ConvertToMp3);
                        }
                        break;
                    case "Dash":
                        var h2 = Helpers.LoadDualSourceHTTPDownloadInfo(entry.Id);
                        if (h2 != null)
                        {
                            this.StartDownload(h2, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, Helpers.GetSpeedLimit(), null);
                        }
                        break;
                    case "Hls":
                        var hls = Helpers.LoadMultiSourceHLSDownloadInfo(entry.Id);
                        if (hls != null)
                        {
                            this.StartDownload(hls, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, Helpers.GetSpeedLimit(), null);
                        }
                        break;
                    case "Mpd-Dash":
                        var dash = Helpers.LoadMultiSourceDASHDownloadInfo(entry.Id);
                        if (dash != null)
                        {
                            this.StartDownload(dash, entry.Name,
                                FileNameFetchMode.None,
                                entry.TargetDir, true, entry.Authentication, entry.Proxy, Helpers.GetSpeedLimit(), null);
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

        private void CheckForUpdate()
        {
            try
            {
                Log.Debug("Checking for updates...");
                if (UpdateChecker.GetAppUpdates(AppVerion, out IList<UpdateInfo> updates, out bool firstUpdate))
                {
                    this.Updates = updates;
                    this.ComponentsInstalled = !firstUpdate;
                    ApplicationContext.Application.ShowUpdateAvailableNotification();
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
            ApplicationContext.Application.ShowMessageBox(null, TextResource.GetText("MSG_IMPORT_DONE"));
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
