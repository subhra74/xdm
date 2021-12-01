//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Text;
//using System.Threading.Tasks;
//using BrowserMonitoring;
//using Newtonsoft.Json;
//using XDM.Core.Lib.Common;
//using XDM.Core.Lib.Downloader;
//using XDM.Core.Lib.Downloader.Dash;
//using XDM.Core.Lib.Downloader.Hls;
//using XDM.Core.Lib.Downloader.Http;
//using XDM.Core.Lib.Downloader.MediaProcessor;
//using XDM.Core.Lib.Downloader.Segmented;
////using XDM.Core.Lib.Downloader.YT.Dash;
//using XDM.Core.Lib.Util;

//namespace XDMApp
//{
//    public class App : IApp
//    {
//        private Dictionary<string, IBaseDownloader> liveDownloads = new Dictionary<string, IBaseDownloader>();
//        private Dictionary<string, RowItem> rows = new Dictionary<string, RowItem>();
//        private IListUI ListView;
//        private Dictionary<string, (DualSourceHTTPDownloadInfo Info, string DisplayText)> ytVideoList = new Dictionary<string, (DualSourceHTTPDownloadInfo Info, string DisplayText)>();
//        private Dictionary<string, (SingleSourceHTTPDownloadInfo Info, string DisplayText)> videoList = new Dictionary<string, (SingleSourceHTTPDownloadInfo Info, string DisplayText)>();

//        public event EventHandler RefreshedLinkReceived;

//        public int ActiveDownloadCount => throw new NotImplementedException();

//        public IAppUI AppUI { get => throw new NotImplementedException(); set => throw new NotImplementedException(); }

//        public string HelpPage => throw new NotImplementedException();

//        public string UpdatePage => throw new NotImplementedException();

//        public IList<UpdateInfo> Updates => throw new NotImplementedException();

//        public bool ComponentsInstalled => throw new NotImplementedException();

//        public bool IsAppUpdateAvailable => throw new NotImplementedException();

//        public string ComponentUpdateText => throw new NotImplementedException();

//        public string IssuePage => throw new NotImplementedException();

//        public string ChromeExtensionUrl => throw new NotImplementedException();

//        public string FirefoxExtensionUrl => throw new NotImplementedException();

//        public string OperaExtensionUrl => throw new NotImplementedException();

//        public string EdgeExtensionUrl => throw new NotImplementedException();

//        public App()
//        {
//            Config.DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), ".xdman");
//            Directory.CreateDirectory(Config.DataDir);
//            //BrowserMonitor.RunHttpIpc(this);
//        }

//        public void LoadDownloadList()
//        {
//            this.rows = ListView.SetListData(
//                JsonConvert.DeserializeObject<List<InProgressDownloadEntry>>(
//                    File.ReadAllText(Path.Combine(
//                        Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
//                        "downloads.json"))));
//            this.ListView.RefereshListView();
//        }

//        public void StartDownload(IBaseDownloader download)
//        {
//            this.liveDownloads.Add(download.Id, download);
//            var id = download.Id;
//            var rowRef = ListView?.AddItemToTop(download.TargetFileName, DateTime.Now.ToShortDateString(),
//                0, Helpers.FormatSize(download.FileSize), id, download.FileSize, DateTime.Now, download.Type);
//            rows[id] = rowRef;
//            download.Probed += HandleProbeResult;
//            download.Finished += DownloadFinished;
//            download.ProgressChanged += DownloadProgressChanged;
//            download.Cancelled += DownloadCancelled;
//            download.Failed += DownloadFailed;
//        }

//        public void SaveState()
//        {
//            var list = ListView.GetListData();
//            File.WriteAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "downloads.json"), JsonConvert.SerializeObject(list));
//        }

//        void DownloadProgressChanged(object source, ProgressResultEventArgs args)
//        {
//            var http = source as IBaseDownloader;
//            if (rows.ContainsKey(http.Id))
//            {
//                ListView.UpdateProgress(rows[http.Id], args.Progress);
//            }
//        }

//        void DownloadFinished(object source, EventArgs args)
//        {
//            var http = source as IBaseDownloader;
//            if (rows.ContainsKey(http.Id))
//            {
//                ListView.DownloadFinished(rows[http.Id], http.FileSize < 0 ? new FileInfo(http.TargetFile).Length : http.FileSize);
//            }
//        }

//        void DownloadFailed(object source, EventArgs args)
//        {
//            var http = source as IBaseDownloader;
//            if (rows.ContainsKey(http.Id))
//            {
//                ListView.DownloadFailed(rows[http.Id]);
//            }

//        }

//        void DownloadCancelled(object source, EventArgs args)
//        {
//            var http = source as IBaseDownloader;
//            if (rows.ContainsKey(http.Id))
//            {
//                ListView.DownloadCanelled(rows[http.Id]);
//            }

//        }

//        void HandleProbeResult(object source, EventArgs args)
//        {
//            var fileSize = "";
//            var http = source as IBaseDownloader;
//            if (http.FileSize > 0)
//            {
//                fileSize = Helpers.FormatSize(http.FileSize);
//            }
//            if (rows.ContainsKey(http.Id))
//            {
//                ListView.UpdateItem(rows[http.Id], http.TargetFileName, fileSize, http.FileSize > 0 ? http.FileSize : 0);
//            }
//        }

//        public void AddDownload(Message message)
//        {
//            Console.WriteLine(JsonConvert.SerializeObject(message));
//            ListView.ShowNewDownloadDialog(message);
//        }

//        public void SetUI(IListUI listUI)
//        {
//            this.ListView = listUI;
//        }

//        public void StopDownloads(List<string> list, bool closeProgressWindow = false)
//        {
//            list.ForEach(id =>
//            {
//                var http = liveDownloads.GetValueOrDefault(id, null);
//                if (http != null)
//                {
//                    http.Stop();
//                    liveDownloads.Remove(id);
//                }
//            });
//        }

//        public void ResumeDownload(Dictionary<string, BaseDownloadEntry> list,
//            bool showProgressWindow = true)
//        {
//            foreach (var item in list)
//            {
//                if (liveDownloads.ContainsKey(item.Key)) return;
//                IBaseDownloader download = null;
//                switch (item.Value.DownloadType)
//                {
//                    case "Http":
//                        download = new SingleSourceHTTPDownloader(item.Key);
//                        break;
//                    case "Dash":
//                        //download = new YTDashDownloader(item.Key);
//                        break;
//                }
//                download.Probed += HandleProbeResult;
//                download.Finished += DownloadFinished;
//                download.ProgressChanged += DownloadProgressChanged;
//                download.Cancelled += DownloadCancelled;
//                download.Failed += DownloadFailed;
//                liveDownloads[item.Key] = download;
//                liveDownloads[item.Key].Resume();
//            }
//        }

//        public void DeleteDownloads(List<string> list)
//        {
//            if (list.Count > 0)
//            {
//                if (ListView.ConfirmDelete($"Delete {list.Count} item{(list.Count > 1 ? "s" : "")}?"))
//                {
//                    var itemsToDelete = new List<RowItem>();
//                    list.ForEach(id =>
//                    {
//                        var http = liveDownloads.GetValueOrDefault(id, null);
//                        if (http != null)
//                        {
//                            http.Stop();
//                            liveDownloads.Remove(id);
//                        }

//                        if (rows.ContainsKey(id))
//                        {
//                            itemsToDelete.Add(rows[id]);
//                            rows.Remove(id);
//                        }
//                    });
//                    ListView.DeleteDownload(itemsToDelete);
//                    SaveState();
//                }
//            }
//        }

//        public void AddVideoNotification(string displayName, DualSourceHTTPDownloadInfo info)
//        {
//            ytVideoList.Add(Guid.NewGuid().ToString(), (info, displayName));
//        }

//        public void AddVideoNotification(string displayName, SingleSourceHTTPDownloadInfo info)
//        {
//            videoList.Add(Guid.NewGuid().ToString(), (info, displayName));
//        }

//        public List<(string ID, string File, string DisplayName)> GetVideoList(bool encode)
//        {
//            var list = new List<(string ID, string File, string DisplayName)>();
//            foreach (var e in ytVideoList)
//            {
//                list.Add((e.Key, EncodeToCharCode(e.Value.Info.File), e.Value.DisplayText));
//            }
//            foreach (var e in videoList)
//            {
//                list.Add((e.Key, EncodeToCharCode(e.Value.Info.File), e.Value.DisplayText));
//            }
//            return list;
//        }

//        private string EncodeToCharCode(string text)
//        {
//            var sb = new StringBuilder();
//            int count = 0;
//            foreach (char ch in text)
//            {
//                if (count > 0)
//                    sb.Append(",");
//                sb.Append((int)ch);
//                count++;
//            }
//            return sb.ToString();
//        }

//        public void StartVideoDownload(string videoId, string name, string folder)
//        {
//            IBaseDownloader downloader = null;
//            if (ytVideoList.ContainsKey(videoId))
//            {
//                //downloader = new YTDashDownloader(ytVideoList[videoId].Info);
//            }
//            else if (videoList.ContainsKey(videoId))
//            {
//                downloader = new SingleSourceHTTPDownloader(videoList[videoId].Info);
//            }
//            if (downloader != null)
//            {
//                downloader.SetFileName(name, FileNameFetchMode.None);
//                downloader.SetTargetDirectory(folder);
//                StartDownload(downloader);
//                downloader.Start();
//            }
//        }

//        public void AddVideoDownload(string videoId)
//        {
//            IBaseDownloader downloader = null;
//            var name = string.Empty;
//            if (ytVideoList.ContainsKey(videoId))
//            {
//                //downloader = new DualSourceHTTPDownloader(ytVideoList[videoId].Info, mediaProcessor: new FFmpegMediaProcessor());
//                name = ytVideoList[videoId].Info.File;
//            }
//            else if (videoList.ContainsKey(videoId))
//            {
//                downloader = new SingleSourceHTTPDownloader(videoList[videoId].Info);
//                name = videoList[videoId].Info.File;
//            }
//            if (downloader != null)
//            {
//                ListView.ShowVideoDownloadDialog(videoId, name);
//            }
//        }

//        public void SetAppUI(IAppUI appUI)
//        {
//            throw new NotImplementedException();
//        }

//        public void SaveInProgressList(List<InProgressDownloadEntry> list)
//        {
//            throw new NotImplementedException();
//        }

//        public void SaveFinishedList(List<FinishedDownloadEntry> list)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(string displayName, MultiSourceHLSDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(string displayName, MultiSourceDASHDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void ClearVideoList()
//        {
//            throw new NotImplementedException();
//        }

//        public bool IsDownloadActive(string id)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(IBaseDownloader download, bool startImmediately)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartVideoDownload(string videoId, string name, string folder, bool startImmediately)
//        {
//            throw new NotImplementedException();
//        }

//        public void SaveInProgressList(IEnumerable<InProgressDownloadEntry> list)
//        {
//            throw new NotImplementedException();
//        }

//        public void SaveFinishedList(IEnumerable<FinishedDownloadEntry> list)
//        {
//            throw new NotImplementedException();
//        }

//        public void SetRenameDownload(string id, string folder, string file)
//        {
//            throw new NotImplementedException();
//        }

//        public void RenameDownload(string id, string folder, string file)
//        {
//            throw new NotImplementedException();
//        }

//        public void WaitFromRefreshedLink(HTTPDownloaderBase downloader)
//        {
//            throw new NotImplementedException();
//        }

//        public void ClearRefreshLinkCandidate()
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(DualSourceHTTPDownloadInfo Info, string DisplayText)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(SingleSourceHTTPDownloadInfo Info, string DisplayText)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(MultiSourceHLSDownloadInfo Info, string DisplayText)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(MultiSourceDASHDownloadInfo Info, string DisplayText)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, DualSourceHTTPDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, SingleSourceHTTPDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, MultiSourceHLSDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotification(StreamingVideoDisplayInfo DisplayInfo, MultiSourceDASHDownloadInfo info)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(MultiSourceHLSDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void AddVideoNotifications(IEnumerable<(MultiSourceDASHDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)> notifications)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(SingleSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(SingleSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(DualSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(MultiSourceHLSDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(MultiSourceDASHDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(SingleSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(DualSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(MultiSourceHLSDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartDownload(MultiSourceDASHDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartVideoDownload(string videoId, string name, string folder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        string IApp.StartDownload(SingleSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        string IApp.StartDownload(DualSourceHTTPDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        string IApp.StartDownload(MultiSourceHLSDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        string IApp.StartDownload(MultiSourceDASHDownloadInfo info, string fileName, FileNameFetchMode fileNameFetchMode, string targetFolder, bool startImmediately, AuthenticationInfo? authentication, ProxyInfo? proxyInfo, DownloadSchedule? schedule, int maxSpeedLimit)
//        {
//            throw new NotImplementedException();
//        }

//        public void ResumeNonInteractiveDownloads(List<string> idList)
//        {
//            throw new NotImplementedException();
//        }

//        public void StartClipboardMonitor()
//        {
//            throw new NotImplementedException();
//        }

//        public void StopClipboardMonitor()
//        {
//            throw new NotImplementedException();
//        }

//        public void ApplyConfig()
//        {
//            throw new NotImplementedException();
//        }

//        public AuthenticationInfo? PromptForCredential(string id, string message)
//        {
//            throw new NotImplementedException();
//        }

//        public void StopDownloads(IEnumerable<string> list, bool closeProgressWindow = false)
//        {
//            throw new NotImplementedException();
//        }

//        public void RestartDownload(BaseDownloadEntry entry)
//        {
//            throw new NotImplementedException();
//        }

//        public string? GetPrimaryUrl(BaseDownloadEntry entry)
//        {
//            throw new NotImplementedException();
//        }

//        public void RemoveDownload(BaseDownloadEntry entry, bool deleteDownloadedFile)
//        {
//            throw new NotImplementedException();
//        }

//        public void ShowProgressWindow(string downloadId)
//        {
//            throw new NotImplementedException();
//        }

//        public void HideProgressWindow(string id)
//        {
//            throw new NotImplementedException();
//        }

//        public void Export(string path)
//        {
//            throw new NotImplementedException();
//        }

//        public void Import(string path)
//        {
//            throw new NotImplementedException();
//        }

//        public string GetLabelText(string key)
//        {
//            throw new NotImplementedException();
//        }
//    }
//}
