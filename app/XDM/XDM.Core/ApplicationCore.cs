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
using XDM.Core.IO;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core
{
    public class ApplicationCore : IApplicationCore
    {
        public Version AppVerion => new(8, 0, 0);
        public string AppPlatform => PlatformHelper.GetAppPlatform();

        private Dictionary<string, (IBaseDownloader Downloader, bool NonInteractive)> liveDownloads = new();
        private GenericOrderedDictionary<string, bool> queuedDownloads = new();
        private GenericOrderedDictionary<string, IProgressWindow> activeProgressWindows = new();
        private Scheduler scheduler;
        private Timer awakePingTimer;
        public int ActiveDownloadCount { get => liveDownloads.Count + queuedDownloads.Count; }

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
            awakePingTimer.Elapsed += (a, b) => PlatformHelper.SendKeepAlivePing();

            try
            {
                QueueManager.Load();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.ToString());
            }

            StartScheduler();
            StartBrowserMonitoring();
        }

        public void StartBrowserMonitoring()
        {
            BrowserMonitor.Run();
        }

        public string? StartDownload(
            IRequestData downloadInfo,
            string fileName,
            FileNameFetchMode fileNameFetchMode,
            string? targetFolder,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo,
            string? queueId,
            bool convertToMp3)
        {
            Log.Debug($"Starting download: {fileName} {fileNameFetchMode} {convertToMp3}");

            IBaseDownloader? http;

            switch (downloadInfo)
            {
                case SingleSourceHTTPDownloadInfo info:
                    http = new SingleSourceHTTPDownloader(info, authentication: authentication,
                        proxy: proxyInfo, mediaProcessor: new FFmpegMediaProcessor(),
                        convertToMp3: convertToMp3);
                    RequestDataIO.SaveDownloadInfo(http.Id!, info);
                    break;
                case DualSourceHTTPDownloadInfo info:
                    http = new DualSourceHTTPDownloader(info, authentication: authentication,
                        proxy: proxyInfo, mediaProcessor: new FFmpegMediaProcessor());
                    RequestDataIO.SaveDownloadInfo(http.Id!, info);
                    break;
                case MultiSourceHLSDownloadInfo info:
                    http = new MultiSourceHLSDownloader(info, authentication: authentication,
                        proxy: proxyInfo, mediaProcessor: new FFmpegMediaProcessor());
                    RequestDataIO.SaveDownloadInfo(http.Id!, info);
                    break;
                case MultiSourceDASHDownloadInfo info:
                    http = new MultiSourceDASHDownloader(info, authentication: authentication,
                        proxy: proxyInfo, mediaProcessor: new FFmpegMediaProcessor());
                    RequestDataIO.SaveDownloadInfo(http.Id!, info);
                    break;
                default:
                    Log.Debug("Unknow request info :: skipping download");
                    return null;
            }

            if (!string.IsNullOrEmpty(queueId))
            {
                QueueManager.AddDownloadsToQueue(queueId!, new string[] { http.Id! });
            }
            http.SetFileName(FileHelper.SanitizeFileName(fileName), fileNameFetchMode);
            http.SetTargetDirectory(targetFolder);
            StartDownload(http, targetFolder, startImmediately, authentication, proxyInfo);
            return http.Id;
        }

        private void StartDownload(IBaseDownloader download,
            string? targetDir,
            bool startImmediately,
            AuthenticationInfo? authentication,
            ProxyInfo? proxyInfo)
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

            ApplicationContext.Application.AddItemToTop(id, download.TargetFileName, targetDir, DateTime.Now,
                download.FileSize, download.Type, download.FileNameFetchMode,
                download.PrimaryUrl?.ToString(), startType, authentication,
                proxyInfo);

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
                    ApplicationContext.Application.RunOnUiThread(() =>
                    {
                        var prgWin = CreateProgressWindow(download);
                        activeProgressWindows[download.Id] = prgWin;
                        prgWin.FileNameText = download.TargetFileName;
                        prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} ...";
                        prgWin.UrlText = download.PrimaryUrl?.ToString() ?? string.Empty;
                        prgWin.ShowProgressWindow();
                    });
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
            var list = new List<IRequestData>(messages.Count);
            foreach (var message in messages)
            {
                var url = message.Url;
                if (string.IsNullOrEmpty(url)) continue;
                var file = FileHelper.SanitizeFileName(message.File ?? FileHelper.GetFileName(new Uri(message.Url)));
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
                var file = FileHelper.SanitizeFileName(message.File ?? FileHelper.GetFileName(new Uri(message.Url)));
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
                    Config.Instance.Proxy, null, false);
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
                    ResumeDownload(new Dictionary<string, DownloadItemBase> { [id] = entry }, true);
                }
            }
        }

        public void ResumeDownload(Dictionary<string, DownloadItemBase> list,
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
                IBaseDownloader? download = null;
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
                    default:
                        continue;
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

        public void StopDownloads(IEnumerable<string> list, bool closeProgressWindow = false)
        {
            var ids = new List<string>(list);
            foreach (var id in ids)
            {
                (var http, _) = liveDownloads.GetValueOrDefault(id);
                if (http != null)
                {
                    http.Stop();
                    //liveDownloads.Remove(id);
                }
                else
                {
                    if (queuedDownloads.ContainsKey(id))
                    {
                        queuedDownloads.Remove(id);
                        ApplicationContext.Application.DownloadCanelled(id);
                        if (activeProgressWindows.ContainsKey(id))
                        {
                            var prgWin = activeProgressWindows[id];
                            prgWin.DownloadCancelled();
                        }
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
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_DOWNLOADING")} {FormattingHelper.FormatSize(args.Downloaded)} / {FormattingHelper.FormatSize(http.FileSize)}";
                    prgWin.DownloadSpeedText = FormattingHelper.FormatSize((long)args.DownloadSpeed) + "/s";
                    prgWin.DownloadETAText = $"{TextResource.GetText("MSG_TIME_LEFT")}: {FormattingHelper.ToHMS(args.Eta)}";
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
                    prgWin.FileSizeText = $"{TextResource.GetText("STAT_ASSEMBLING")} {FormattingHelper.FormatSize(args.Downloaded)} / {FormattingHelper.FormatSize(http.FileSize)}";
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
                RemoveStateFiles(http.Id, false);
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
                    PlatformHelper.RunAntivirus(Config.Instance.AntiVirusExecutable, Config.Instance.AntiVirusArgs, http.TargetFile);
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
                    prgWin.FileSizeText =
                        $"{TextResource.GetText("STAT_DOWNLOADING")} {FormattingHelper.FormatSize(0)} / {FormattingHelper.FormatSize(http.FileSize)}";
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
                    ResumeDownload(new Dictionary<string, DownloadItemBase> { [kv.Key] = entry }, kv.Value);
                }
            }
            else
            {
                if (Config.Instance.ShutdownAfterAllFinished)
                {
                    PlatformHelper.ShutDownPC();
                }
                if (awakePingTimer.Enabled)
                {
                    Log.Debug("Stopping keep awake timer");
                    awakePingTimer.Stop();
                }
                if (Config.Instance.RunCommandAfterCompletion)
                {
                    PlatformHelper.RunCommand(Config.Instance.AfterCompletionCommand);
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

        public string? GetPrimaryUrl(DownloadItemBase entry)
        {
            if (entry == null) return null;
            switch (entry.DownloadType)
            {
                case "Http":
                    var h1 = RequestDataIO.LoadSingleSourceHTTPDownloadInfo(entry.Id);// LoadInfo<SingleSourceHTTPDownloadInfo>(entry.Id);
                    if (h1 != null)
                    {
                        return h1.Uri;
                    }
                    break;
                case "Dash":
                    var h2 = RequestDataIO.LoadDualSourceHTTPDownloadInfo(entry.Id);// LoadInfo<DualSourceHTTPDownloadInfo>(entry.Id);
                    if (h2 != null)
                    {
                        return h2.Uri1;
                    }
                    break;
                case "Hls":
                    var hls = RequestDataIO.LoadMultiSourceHLSDownloadInfo(entry.Id);// LoadInfo<MultiSourceHLSDownloadInfo>(entry.Id);
                    if (hls != null)
                    {
                        return hls.VideoUri;
                    }
                    break;
                case "Mpd-Dash":
                    var dash = RequestDataIO.LoadMultiSourceDASHDownloadInfo(entry.Id); //LoadInfo<MultiSourceDASHDownloadInfo>(entry.Id);
                    if (dash != null)
                    {
                        return dash.Url;
                    }
                    break;
            }

            return null;
        }

        private List<string> GetStateFiles(string id, bool deleteInfo)
        {
            var files = new List<string>();
            if (deleteInfo)
            {
                files.Add(Path.Combine(Config.DataDir, id + ".info"));
            }
            files.Add(Path.Combine(Config.DataDir, id + ".state"));
            files.Add(Path.Combine(Config.DataDir, id + ".state.1"));
            files.Add(Path.Combine(Config.DataDir, id + ".state.2"));
            files.AddRange(Directory.EnumerateFiles(Config.DataDir, id + ".state.3.*"));
            return files;
        }

        private void RemoveStateFiles(string id, bool deleteInfo)
        {
            var stateFiles = GetStateFiles(id, deleteInfo);

            foreach (var file in stateFiles)
            {
                if (File.Exists(file))
                {
                    try
                    {
                        File.Delete(file);
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                }
            }
        }

        public void RemoveDownload(DownloadItemBase entry, bool deleteDownloadedFile, bool removeInfo = true)
        {
            try
            {
                if (entry == null) return;
                string? tempDir = null;
                var validEntry = false;
                switch (entry.DownloadType)
                {
                    case "Http":
                        var h1 = DownloadStateIO.LoadSingleSourceHTTPDownloaderState(entry.Id);
                        if (h1 != null)
                        {
                            tempDir = h1.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Dash":
                        var h2 = DownloadStateIO.LoadDualSourceHTTPDownloaderState(entry.Id);
                        if (h2 != null)
                        {
                            tempDir = h2.TempDir;
                            validEntry = true;
                        }
                        break;
                    case "Hls":
                        var hls = DownloadStateIO.LoadMultiSourceHLSDownloadState(entry.Id);
                        if (hls != null)
                        {
                            tempDir = hls.TempDirectory;
                            validEntry = true;
                        }
                        break;
                    case "Mpd-Dash":
                        var dash = DownloadStateIO.LoadMultiSourceDASHDownloadState(entry.Id);
                        if (dash != null)
                        {
                            tempDir = dash.TempDirectory;
                            validEntry = true;
                        }
                        break;
                }

                if (validEntry)
                {
                    RemoveStateFiles(entry.Id, removeInfo);

                    if (entry is FinishedDownloadItem && deleteDownloadedFile)
                    {
                        var file = Path.Combine(entry.TargetDir, entry.Name);
                        if (File.Exists(file))
                        {
                            File.Delete(file);
                        }
                    }

                    try
                    {
                        if (Directory.Exists(tempDir) && !string.IsNullOrEmpty(tempDir))
                        {
                            Directory.Delete(tempDir, true);
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        public void RestartDownload(DownloadItemBase entry)
        {
            if (entry == null) return;
            var convertToMp3 = false;
            IRequestData? request;
            try
            {
                switch (entry.DownloadType)
                {
                    case "Http":
                        var info = RequestDataIO.LoadSingleSourceHTTPDownloadInfo(entry.Id);
                        request = info;
                        convertToMp3 = info?.ConvertToMp3 ?? false;
                        break;
                    case "Dash":
                        request = RequestDataIO.LoadDualSourceHTTPDownloadInfo(entry.Id);
                        break;
                    case "Hls":
                        request = RequestDataIO.LoadMultiSourceHLSDownloadInfo(entry.Id);
                        break;
                    case "Mpd-Dash":
                        request = RequestDataIO.LoadMultiSourceDASHDownloadInfo(entry.Id);
                        break;
                    default:
                        request = null;
                        break;
                }

                if (request != null)
                {
                    this.StartDownload(request, entry.Name,
                                   FileNameFetchMode.FileNameAndExtension,
                                   entry.TargetDir, true, entry.Authentication, entry.Proxy, null,
                                   convertToMp3);
                    RemoveDownload(entry, false, false);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error restarting download");
            }
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
    }
}
