//using System;
//using System.Collections.Concurrent;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Net;
//using System.Net.Http;
//using System.Threading;
//
//using XDM.Core.Lib.Common;
//using Newtonsoft.Json;
//using XDM.Core.Lib.Util;
//using XDM.Core.Lib.Downloader.Segmented;

//namespace XDM.Core.Lib.Downloader.YT.Dash
//{
//    public class YTDashDownloader : IBaseDownloader
//    {
//        private HttpClient _http;
//        private List<YtDashChunk> _chunks;
//        private CancellationTokenSource _cancellationTokenSource;
//        private CancellationTokenSource _cancellationTokenSourceStateSaver;
//        private SimpleStreamMap _chunkStreamMap;
//        private ICancelRequster _cancelRequestor;
//        private FileNameFetchMode _fileNameFetchMode = FileNameFetchMode.FileNameAndExtension;
//        private long lastUpdated = Environment.TickCount64;
//        private readonly ProgressResultEventArgs progressResult;
//        private ProbeResult[] probeResults;
//        private YTDashDownloadMetadata _state;

//        public bool IsCancelled => _cancellationTokenSource.IsCancellationRequested;
//        public string Id { get; private set; }
//        public long FileSize => _state.FileSize;
//        public string TargetFile
//        {
//            get
//            {
//                if (_state.TargetDir == null || _state.FileName == null)
//                {
//                    return null;
//                }
//                return Path.Combine(_state.TargetDir,
//                    _state.FileName);
//            }
//        }

//        public string TargetFileName
//        {
//            get
//            {
//                return _state.FileName;
//            }
//        }

//        public string Type => "Dash";

//        public event EventHandler Probed;
//        public event EventHandler Finished;
//        public event EventHandler<ProgressResultEventArgs> ProgressChanged;
//        public event EventHandler Cancelled;
//        public event EventHandler<DownloadFailedEventArgs> Failed;
//        public event EventHandler<ProgressResultEventArgs> AssembingProgressChanged;

//        public YTDashDownloader(DualSourceHTTPDownloadInfo info, HttpClient http = null)
//        {
//            Id = Guid.NewGuid().ToString();

//            _cancellationTokenSource = new CancellationTokenSource();
//            _cancellationTokenSourceStateSaver = new CancellationTokenSource();

//            var cookieContainer = new CookieContainer();
//            _http = http ?? new HttpClient(new HttpClientHandler { CookieContainer = cookieContainer });
//            _http.DefaultRequestVersion = HttpVersion.Version20;

//            progressResult = new ProgressResultEventArgs();

//            var uri1 = new Uri(info.Uri1);
//            var uri2 = new Uri(info.Uri2);

//            _state = new YTDashDownloadMetadata
//            {
//                Url1 = uri1,
//                Url2 = uri2,
//                Id = Id,
//                Cookies1 = info.Cookies1,
//                Cookies2 = info.Cookies2,
//                Headers1 = info.Headers1,
//                Headers2 = info.Headers2,
//                FileName = info.File ?? Helpers.GetFileName(uri1)
//            };

//            if (info.Cookies1 != null)
//            {
//                foreach (var cookie in info.Cookies1)
//                {
//                    cookieContainer.Add(uri1, new Cookie
//                    {
//                        Name = cookie.Key,
//                        Value = cookie.Value
//                    });
//                }
//            }

//            if (info.Cookies2 != null)
//            {
//                foreach (var cookie in info.Cookies2)
//                {
//                    cookieContainer.Add(uri2, new Cookie
//                    {
//                        Name = cookie.Key,
//                        Value = cookie.Value
//                    });
//                }
//            }
//        }

//        public YTDashDownloader(string id, HttpClient http = null)
//        {
//            Id = id;

//            _cancellationTokenSource = new CancellationTokenSource();
//            _cancellationTokenSourceStateSaver = new CancellationTokenSource();
//            _http = http ?? new HttpClient();
//            progressResult = new ProgressResultEventArgs();
//        }

//        public async void Start()
//        {
//            File.WriteAllText(Path.Combine(Config.DataDir, Id + ".state"), JsonConvert.SerializeObject(_state));
//            await DownloadAsyncImpl();
//        }

//        public void Stop()
//        {
//            SaveChunkState();
//            _cancellationTokenSourceStateSaver.Cancel();
//            _cancellationTokenSource.Cancel();
//            _http.CancelPendingRequests();
//            _http.Dispose();
//        }

//        public async void Resume()
//        {
//            try
//            {
//                RestoreState();
//                if (_chunks == null)
//                {
//                    await DownloadAsyncImpl();
//                }
//                else
//                {
//                    _cancelRequestor = new CancelRequestor(_cancellationTokenSource);
//                    await DownloadChunksAsync();
//                }
//                Finished?.Invoke(this, EventArgs.Empty);
//            }
//            catch (OperationCanceledException ex)
//            {
//                Console.WriteLine(ex);
//                Cancelled?.Invoke(this, EventArgs.Empty);
//            }
//            catch (HttpRequestException ex)
//            {
//                Console.WriteLine(ex);
//                Failed?.Invoke(this, new DownloadFailedEventArgs(1000, ex.Message));
//            }
//            catch (Exception ex)
//            {
//                Console.WriteLine(ex);
//                if (ex.InnerException is HttpRequestException)
//                {
//                    var he = ex.InnerException as HttpRequestException;
//                    Failed?.Invoke(this, new DownloadFailedEventArgs(5000, $"Server returned invalid response: {he.StatusCode}"));
//                }
//                else
//                {
//                    Failed?.Invoke(this, new DownloadFailedEventArgs(6000, "Error"));
//                }
//            }
//        }

//        private void SaveChunkState()
//        {
//            if (_chunks == null) return;
//            lock (this)
//            {
//                File.WriteAllText(Path.Combine(Config.DataDir, Id + ".chunks"),
//                    JsonConvert.SerializeObject(_chunks));
//            }
//        }

//        private void RestoreState()
//        {
//            _state = JsonConvert.DeserializeObject<YTDashDownloadMetadata>(
//                                File.ReadAllText(Path.Combine(Config.DataDir, Id + ".state")));

//            try
//            {
//                _chunks = JsonConvert.DeserializeObject<List<YtDashChunk>>(
//                    File.ReadAllText(Path.Combine(Config.DataDir, Id + ".chunks")));

//                var streamMap = _chunks.Select(c => new { c.Id, TempFilePath = TargetFile + (c.First ? ".part1" : ".part2") })
//                    .ToDictionary(e => e.Id, e =>
//                        e.TempFilePath);
//                _chunkStreamMap = new SimpleStreamMap { StreamMap = streamMap };
//            }
//            catch
//            {
//                // ignored
//                Console.WriteLine("Chunk restore failed");
//            }
//        }

//        private async Task DownloadChunkAsync(Chunk chunk, SemaphoreSlim semaphore)
//        {
//            var chunkDownloader = new HttpChunkDownloader(chunk, _http, _cancellationTokenSource.Token,
//                _chunkStreamMap,
//                _cancelRequestor);
//            try
//            {
//                chunkDownloader.ChunkDataReceived += ChunkDataReceived;
//                await chunkDownloader.DoownloadAsync().ConfigureAwait(false);
//            }
//            finally
//            {
//                chunkDownloader.ChunkDataReceived -= ChunkDataReceived;
//                semaphore.Release();
//            }
//        }

//        private async Task SaveStatePeriodicAsync(CancellationToken token)
//        {
//            while (!token.IsCancellationRequested)
//            {
//                try
//                {
//                    await Task.Delay(5000, token);
//                }
//                catch (TaskCanceledException)
//                {
//                    Console.WriteLine("Finished");
//                    break;
//                }
//                SaveChunkState();
//            }
//        }

//        private async Task DownloadChunksAsync()
//        {
//            SemaphoreSlim semaphore = new SemaphoreSlim(8, 8);
//            List<Task> downloadTasks = new List<Task>();

//            Task saveSaverTask = SaveStatePeriodicAsync(_cancellationTokenSourceStateSaver.Token);

//            foreach (var chunk in _chunks)
//            {
//                if (chunk.ChunkState == ChunkState.Finished) continue;

//                try
//                {
//                    await semaphore.WaitAsync(_cancellationTokenSource.Token).ConfigureAwait(false);
//                }
//                catch (OperationCanceledException)
//                {
//                    break;
//                }
//                downloadTasks.Add(DownloadChunkAsync(chunk, semaphore));
//                if (_cancellationTokenSource.IsCancellationRequested) break;
//            }
//            await Task.WhenAll(downloadTasks).ConfigureAwait(false);
//            _cancellationTokenSourceStateSaver.Cancel();

//            await saveSaverTask;
//        }

//        public async Task ProbeTargetAsync(bool swallowException = true)
//        {
//            try
//            {
//                var t1 = new HttpProbe().ProbeAsync(_state.Url1, _http,
//                    _cancellationTokenSource.Token, this._state.Headers1);
//                var t2 = new HttpProbe().ProbeAsync(_state.Url2, _http,
//                    _cancellationTokenSource.Token, this._state.Headers2);

//                probeResults = await Task.WhenAll(new List<Task<ProbeResult>> { t1, t2 });

//                _state.FileName += ".mkv";
//                if (probeResults[0].ResourceSize.HasValue && probeResults[1].ResourceSize.HasValue)
//                    _state.FileSize = probeResults[0].ResourceSize.Value + probeResults[1].ResourceSize.Value;

//                if (string.IsNullOrWhiteSpace(_state.TargetDir))
//                {
//                    _state.TargetDir = Helpers.GetDownloadFolderByPath(_state.FileName);
//                }

//                _state.Url1 = probeResults[0].FinalUri;
//                _state.Url2 = probeResults[1].FinalUri;

//                var probeEventHandler = Probed;
//                probeEventHandler?.Invoke(this, EventArgs.Empty);
//            }
//            catch { if (!swallowException) throw; }
//        }

//        private async Task DownloadAsyncImpl()
//        {
//            try
//            {
//                _cancelRequestor = new CancelRequestor(_cancellationTokenSource);
//                _chunkStreamMap = new SimpleStreamMap
//                {
//                    StreamMap = new Dictionary<string, string>()
//                };

//                if (probeResults == null)
//                {
//                    await ProbeTargetAsync(false);
//                }

//                var probeEventHandler = Probed;
//                probeEventHandler?.Invoke(this, EventArgs.Empty);

//                var resumable = probeResults[0].Resumable && probeResults[1].Resumable;
//                var hasResourceSize = probeResults[0].ResourceSize.HasValue && probeResults[1].ResourceSize.HasValue;

//                if (!resumable)
//                {
//                    if (probeResults[0].ResourceSize.HasValue && probeResults[1].ResourceSize.HasValue)
//                        _state.FileSize = probeResults[0].ResourceSize.Value + probeResults[1].ResourceSize.Value;
//                    await File.WriteAllTextAsync(Path.Combine(Config.DataDir, Id + ".state"), JsonConvert.SerializeObject(_state)).ConfigureAwait(false);
//                    var t1 = DownloadNonResumableContent(true, probeResults[0].Response, _cancellationTokenSource.Token);
//                    var t2 = DownloadNonResumableContent(false, probeResults[1].Response, _cancellationTokenSource.Token);
//                    await Task.WhenAll(t1, t2).ConfigureAwait(false);
//                    Finished?.Invoke(this, EventArgs.Empty);
//                    return;
//                }
//                if (resumable && hasResourceSize)
//                {
//                    _state.FileSize = probeResults[0].ResourceSize.Value + probeResults[1].ResourceSize.Value;
//                    await File.WriteAllTextAsync(Path.Combine(Config.DataDir, Id + ".state"), JsonConvert.SerializeObject(_state)).ConfigureAwait(false);

//                    if (!Directory.Exists(_state.TargetDir))
//                    {
//                        Directory.CreateDirectory(_state.TargetDir);
//                    }
//                    await using var targetStream1 = new FileStream(TargetFile + ".part1", FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
//                    targetStream1.SetLength(probeResults[0].ResourceSize.Value);
//                    targetStream1.Close();

//                    await using var targetStream2 = new FileStream(TargetFile + ".part2", FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
//                    targetStream2.SetLength(probeResults[1].ResourceSize.Value);
//                    targetStream2.Close();

//                    int chunkSize = 512 * 1024;
//                    //int chunkSize = 10*1024 * 1024;
//                    _chunks = new List<YtDashChunk>();
//                    long rem1 = probeResults[0].ResourceSize.Value;
//                    long rem2 = probeResults[1].ResourceSize.Value;
//                    long offset1 = 0;
//                    long offset2 = 0;
//                    while (rem1 > 0 || rem2 > 0)
//                    {
//                        if (rem1 > 0)
//                        {
//                            long cs1 = Math.Min(chunkSize, rem1);
//                            var chunk1 = new YtDashChunk
//                            {
//                                Uri = _state.Url1,
//                                ChunkState = ChunkState.Ready,
//                                Id = Guid.NewGuid().ToString(),
//                                Offset = offset1,
//                                Size = cs1,
//                                First = true
//                            };
//                            _chunks.Add(chunk1);
//                            _chunkStreamMap.StreamMap[chunk1.Id] = TargetFile + ".part1";
//                            rem1 -= cs1;
//                            offset1 += cs1;
//                        }
//                        if (rem2 > 0)
//                        {
//                            long cs2 = Math.Min(chunkSize, rem2);
//                            var chunk2 = new YtDashChunk
//                            {
//                                Uri = _state.Url2,
//                                ChunkState = ChunkState.Ready,
//                                Id = Guid.NewGuid().ToString(),
//                                Offset = offset2,
//                                Size = cs2,
//                                First = false
//                            };
//                            _chunks.Add(chunk2);
//                            _chunkStreamMap.StreamMap[chunk2.Id] = TargetFile + ".part2";
//                            rem2 -= cs2;
//                            offset2 += cs2;
//                        }
//                    }

//                    await DownloadChunksAsync().ConfigureAwait(false);
//                    Finished?.Invoke(this, EventArgs.Empty);
//                }
//            }
//            catch (OperationCanceledException ex)
//            {
//                Console.WriteLine(ex);
//                Cancelled?.Invoke(this, EventArgs.Empty);
//            }
//            catch (HttpRequestException ex)
//            {
//                Console.WriteLine(ex);
//                Failed?.Invoke(this, new DownloadFailedEventArgs(1000, ex.Message));
//            }
//            catch (Exception ex)
//            {
//                Console.WriteLine(ex);
//                if (ex.InnerException is HttpRequestException)
//                {
//                    var he = ex.InnerException as HttpRequestException;
//                    Failed?.Invoke(this, new DownloadFailedEventArgs(5000, $"Server returned invalid response: {he.StatusCode}"));
//                }
//                else
//                {
//                    Failed?.Invoke(this, new DownloadFailedEventArgs(6000, "Error"));
//                }
//            }
//        }

//        private async Task DownloadNonResumableContent(bool first, HttpResponseMessage message, CancellationToken token)
//        {
//            byte[] buffer = new byte[8192];
//            await using var sourceStream = await message.Content.ReadAsStreamAsync().ConfigureAwait(false);
//            await using var targetStream = new FileStream(TargetFile + (first ? ".part1" : ".part2"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.ReadWrite);
//            while (!token.IsCancellationRequested)
//            {
//                int x = await sourceStream.ReadAsync(buffer, 0, buffer.Length, token)
//                    .ConfigureAwait(false);
//                if (x == 0)
//                {
//                    return;
//                }

//                await targetStream.WriteAsync(buffer, 0, x, token).ConfigureAwait(false);
//            }
//        }

//        private void ChunkDataReceived(object sender, EventArgs args)
//        {
//            lock (this)
//            {
//                long tick = Environment.TickCount64;
//                if (tick - lastUpdated > 1000)
//                {
//                    lastUpdated = tick;
//                    var downloaded = _chunks.Sum(chunk => chunk.Downloaded);
//                    progressResult.Progress = (int)(downloaded * 100 / FileSize);
//                    ProgressChanged?.Invoke(this, progressResult);
//                }
//            }
//        }

//        public void SetFileName(string name, bool fetchExtension = false)
//        {
//            if (fetchExtension)
//            {
//                _fileNameFetchMode = FileNameFetchMode.ExtensionOnly;
//            }
//            else
//            {
//                _fileNameFetchMode = FileNameFetchMode.None;
//            }
//            _state.FileName = name;
//        }

//        public void SetTargetDirectory(string folder)
//        {
//            _state.TargetDir = folder;
//        }

//        public void SetUserSelectedFile(string file)
//        {
//            var folder = Path.GetDirectoryName(file);
//            var name = Path.GetFileName(file);
//            _state.FileName = name;
//            _state.TargetDir = folder;
//        }

//        internal class YTDashDownloadMetadata
//        {
//            public Uri Url1;
//            public Uri Url2;
//            public string TargetDir;
//            public string FileName;
//            public string Id;
//            public Dictionary<string, List<string>> Headers1;
//            public Dictionary<string, List<string>> Headers2;
//            public Dictionary<string, string> Cookies1;
//            public Dictionary<string, string> Cookies2;
//            public long FileSize = -1;
//        }

//        public class YtDashChunk : Chunk
//        {
//            public bool First { get; set; }
//        }
//    }
//}
