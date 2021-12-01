//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Linq;
//using System.Net;
//using System.Net.Http;
//using System.Net.Http.Headers;
//using System.Text;
//using System.Threading;
//
//using MediaParser.Hls;
//using Newtonsoft.Json;
//using Serilog;
//using XDM.Core.Lib.Common;
//using XDM.Core.Lib.Downloader.MediaProcessor;
//using XDM.Core.Lib.Downloader.Segmented;
//using XDM.Core.Lib.Util;



//namespace XDM.Core.Lib.Downloader.Hls
//{
//    public class HlsDownloader : IBaseDownloader
//    {
//        private HttpClient _http;
//        private List<HlsChunk> _chunks;
//        private CancellationTokenSource _cancellationTokenSource;
//        private CancellationTokenSource _cancellationTokenSourceStateSaver;
//        private SimpleStreamMap _chunkStreamMap;
//        private ICancelRequster _cancelRequestor;
//        private FileNameFetchMode _fileNameFetchMode = FileNameFetchMode.FileNameAndExtension;
//        private long lastUpdated = Environment.TickCount64;
//        private readonly ProgressResultEventArgs progressResult;
//        private HlsDownloadState _state;
//        private HlsPlaylist pl1, pl2;
//        private Dictionary<Uri, byte[]> keyMap;
//        public bool IsCancelled => _cancellationTokenSource.IsCancellationRequested;
//        public string Id { get; private set; }
//        public long FileSize => _state.FileSize;
//        public double Duration => this._state.Duration;
//        public FileNameFetchMode FileNameFetchMode
//        {
//            get { return _fileNameFetchMode; }
//            set { _fileNameFetchMode = value; }
//        }
//        public string TargetFile => Path.Combine(TargetDir,
//                    TargetFileName);
//        public string TargetFileName { get; set; }
//        public string TargetDir { get; set; }

//        public string Type => "Hls";

//        public event EventHandler Probed;
//        public event EventHandler Finished;
//        public event EventHandler Started;
//        public event EventHandler<ProgressResultEventArgs> ProgressChanged;
//        public event EventHandler Cancelled;
//        public event EventHandler<DownloadFailedEventArgs> Failed;
//        public event EventHandler<ProgressResultEventArgs> AssembingProgressChanged;
//        private BaseMediaProcessor mediaProcessor;

//        public HlsDownloader(MultiSourceHLSDownloadInfo info, HttpClient http = null,
//            BaseMediaProcessor mediaProcessor = null)
//        {
//            Id = Guid.NewGuid().ToString();

//            _cancellationTokenSource = new CancellationTokenSource();
//            _cancellationTokenSourceStateSaver = new CancellationTokenSource();

//            var cookieContainer = new CookieContainer();
//            _http = http ?? new HttpClient(new HttpClientHandler { CookieContainer = cookieContainer });
//            _http.DefaultRequestVersion = HttpVersion.Version20;

//            progressResult = new ProgressResultEventArgs();

//            _state = new HlsDownloadState
//            {
//                PlayListContainer = info.PlaylistContainer,
//                Id = Id,
//                Cookies = info.Cookies,
//                Headers = info.Headers,
//            };

//            if (info.Cookies != null)
//            {
//                foreach (var cookie in info.Cookies)
//                {
//                    cookieContainer.Add(info.PlaylistContainer.VideoPlaylist, new Cookie
//                    {
//                        Name = cookie.Key,
//                        Value = cookie.Value
//                    });
//                    if (info.PlaylistContainer.AudioPlaylist != null)
//                    {
//                        cookieContainer.Add(info.PlaylistContainer.AudioPlaylist, new Cookie
//                        {
//                            Name = cookie.Key,
//                            Value = cookie.Value
//                        });
//                    }
//                }
//            }

//            this.mediaProcessor = mediaProcessor;
//        }

//        public HlsDownloader(string id, HttpClient http = null, BaseMediaProcessor mediaProcessor = null)
//        {
//            Id = id;

//            _cancellationTokenSource = new CancellationTokenSource();
//            _cancellationTokenSourceStateSaver = new CancellationTokenSource();
//            _http = http ?? new HttpClient();
//            progressResult = new ProgressResultEventArgs();
//            this.mediaProcessor = mediaProcessor;
//        }

//        public async void Start()
//        {
//            File.WriteAllText(Path.Combine(Config.DataDir, Id + ".state"), JsonConvert.SerializeObject(_state));
//            Started?.Invoke(this, EventArgs.Empty);
//            await DownloadAsyncImpl();
//        }

//        public void Stop()
//        {
//            _cancellationTokenSourceStateSaver.Cancel();
//            SaveChunkState();
//            _cancellationTokenSource.Cancel();
//            _http.CancelPendingRequests();
//            _http.Dispose();
//        }

//        public async void Resume()
//        {
//            try
//            {
//                Started?.Invoke(this, EventArgs.Empty);
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

//                var count = CreateFileList();
//                Assemble(count);
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
//            _state = JsonConvert.DeserializeObject<HlsDownloadState>(
//                                File.ReadAllText(Path.Combine(Config.DataDir, Id + ".state")));
//            if (_state.IsEncrypted)
//            {
//                this.keyMap = JsonConvert.DeserializeObject<Dictionary<Uri, byte[]>>(
//                                    File.ReadAllText(Path.Combine(Config.DataDir, Id + ".keys")));
//            }

//            try
//            {
//                _chunks = JsonConvert.DeserializeObject<List<HlsChunk>>(
//                    File.ReadAllText(Path.Combine(Config.DataDir, Id + ".chunks")));

//                var hlsDir = Path.Combine(Config.DataDir, Id);

//                var streamMap = _chunks.Select(c => new { c.Id, TempFilePath = Path.Combine(hlsDir, (c.First ? "1_" : "2_") + Helpers.GetFileName(c.Uri)) })
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

//        public async Task ProbeTargetAsync(bool swallowException = true)
//        {
//            try
//            {
//                var tasks = new List<Task<HttpResponseMessage>>();
//                var request1 = new HttpRequestMessage(HttpMethod.Get, _state.PlayListContainer.VideoPlaylist);
//                request1.Version = HttpVersion.Version20;
//                if (this._state.Headers != null)
//                {
//                    foreach (var header in this._state.Headers)
//                    {
//                        request1.Headers.Add(header.Key, header.Value);
//                    }
//                }
//                Log.Information("Downloading 1st url in HLS: "+request1);
//                tasks.Add(_http.SendAsync(request1, _cancellationTokenSource.Token));

//                if (_state.PlayListContainer.AudioPlaylist != null)
//                {
//                    var request2 = new HttpRequestMessage(HttpMethod.Get, _state.PlayListContainer.AudioPlaylist);
//                    Log.Information("Downloading 2nd url in HLS: " + request2);
//                    request2.Version = HttpVersion.Version20;
//                    if (this._state.Headers != null)
//                    {
//                        foreach (var header in this._state.Headers)
//                        {
//                            request2.Headers.Add(header.Key, header.Value);
//                        }
//                    }
//                    tasks.Add(_http.SendAsync(request2, _cancellationTokenSource.Token));
//                }

//                var results = await Task.WhenAll(tasks);

//                if (results[0].StatusCode != HttpStatusCode.OK || (results.Length == 2 && results[1].StatusCode != HttpStatusCode.OK))
//                {
//                    var statusCode = results[0].StatusCode != HttpStatusCode.OK ? results[0].StatusCode : results[1].StatusCode;
//                    var failedResponse = results[0].StatusCode != HttpStatusCode.OK ? results[0] : results[1];
//                    throw new Exception($"Invalid response code: {statusCode}",
//                        new HttpRequestException(failedResponse.ReasonPhrase, null, statusCode));
//                }

//                var pl1 = HlsParser.ParseMediaSegments((await results[0].Content.ReadAsStringAsync()).Split('\n'),
//                    _state.PlayListContainer.VideoPlaylist.ToString());
//                _state.Duration = pl1.TotalDuration;

//                if (results.Length == 2)
//                {
//                    pl2 = HlsParser.ParseMediaSegments((await results[1].Content.ReadAsStringAsync()).Split('\n'),
//                    _state.PlayListContainer.AudioPlaylist.ToString());
//                }

//                if (string.IsNullOrWhiteSpace(this.TargetDir))
//                {
//                    this.TargetDir = Helpers.GetDownloadFolderByPath(this.TargetFileName);
//                }

//                var probeEventHandler = Probed;
//                probeEventHandler?.Invoke(this, EventArgs.Empty);
//                this.pl1 = pl1;
//            }
//            catch { if (!swallowException) throw; }
//        }

//        private HlsChunk CreateChunk(HlsMediaSegment mediaSegment, bool hasByteRange, bool first)
//        {
//            return new HlsChunk
//            {
//                Uri = mediaSegment.Url,
//                ChunkState = ChunkState.Ready,
//                Id = Guid.NewGuid().ToString(),
//                Offset = hasByteRange ? mediaSegment.ByteRange.start : 0,
//                Size = hasByteRange ? mediaSegment.ByteRange.end : -1,
//                Duration = mediaSegment.Duration,
//                First = first,
//                KeyUrl = mediaSegment.KeyUrl,
//                IV = mediaSegment.IV
//            };
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

//                if (pl1 == null)
//                {
//                    await ProbeTargetAsync(false);
//                }

//                var probeEventHandler = Probed;
//                probeEventHandler?.Invoke(this, EventArgs.Empty);

//                _state.FileSize = -1;
//                _state.Duration = pl1.TotalDuration;
//                await File.WriteAllTextAsync(Path.Combine(Config.DataDir, Id + ".state"), JsonConvert.SerializeObject(_state)).ConfigureAwait(false);

//                if (!Directory.Exists(this.TargetDir))
//                {
//                    Directory.CreateDirectory(this.TargetDir);
//                }

//                var hlsDir = Path.Combine(Config.DataDir, Id);
//                Directory.CreateDirectory(hlsDir);
//                Console.WriteLine(hlsDir);

//                _chunks = new List<HlsChunk>();
//                var i = 0;
//                for (; i < Math.Min(pl1.MediaSegments.Count, pl2 != null ? pl2.MediaSegments.Count : Int64.MaxValue); i++)
//                {
//                    var chunk = CreateChunk(pl1.MediaSegments[i], pl1.HasByteRange, true);
//                    _chunks.Add(chunk);
//                    _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(hlsDir, "1_" + Helpers.GetFileName(chunk.Uri));

//                    if (pl2 != null)
//                    {
//                        var chunk1 = CreateChunk(pl2.MediaSegments[i], pl2.HasByteRange, false);
//                        _chunks.Add(chunk1);
//                        _chunkStreamMap.StreamMap[chunk1.Id] = Path.Combine(hlsDir, "2_" + Helpers.GetFileName(chunk1.Uri));
//                    }
//                }

//                if (i < pl1.MediaSegments.Count)
//                {
//                    for (; i < pl1.MediaSegments.Count; i++)
//                    {
//                        var chunk = CreateChunk(pl1.MediaSegments[i], pl1.HasByteRange, true);
//                        _chunks.Add(chunk);
//                        _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(hlsDir, "1_" + Helpers.GetFileName(chunk.Uri));
//                    }
//                }
//                else if (pl2 != null)
//                {
//                    for (; i < pl2.MediaSegments.Count; i++)
//                    {
//                        var chunk = CreateChunk(pl2.MediaSegments[i], pl2.HasByteRange, false);
//                        _chunks.Add(chunk);
//                        _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(hlsDir, "2_" + Helpers.GetFileName(chunk.Uri));
//                    }
//                }

//                if (pl1.IsEncrypted || pl2 != null && pl2.IsEncrypted)
//                {
//                    _state.IsEncrypted = true;
//                    this.keyMap = await GetEncryptionKeys();
//                    File.WriteAllText(Path.Combine(Config.DataDir, Id + ".keys"), JsonConvert.SerializeObject(keyMap));
//                }

//                await DownloadChunksAsync().ConfigureAwait(false);

//                //incase of m4s files, simply concat audio video segments to respective streams and then merge with ffmpeg
//                if (IsFragmentedMP4())
//                {
//                    Assemble(-1);
//                }
//                else
//                {
//                    var count = CreateFileList();
//                    Assemble(count);
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

//        private async Task<Dictionary<Uri, byte[]>> GetEncryptionKeys()
//        {
//            var keyMap = new Dictionary<Uri, byte[]>();
//            foreach (var chunk in _chunks)
//            {
//                var keyUrl = chunk.KeyUrl;
//                if (keyUrl != null)
//                {
//                    var res = await _http.GetAsync(keyUrl).ConfigureAwait(false);
//                    res.EnsureSuccessStatusCode();
//                    var keyBytes = await res.Content.ReadAsByteArrayAsync();
//                    keyMap[keyUrl] = keyBytes;
//                }
//            }
//            return keyMap;
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

//        private void ChunkDataReceived(object sender, EventArgs args)
//        {
//            lock (this)
//            {
//                long tick = Environment.TickCount64;
//                if (tick - lastUpdated > 1000)
//                {
//                    lastUpdated = tick;
//                    var downloaded = _chunks.FindAll(c => c.ChunkState == ChunkState.Finished).Count;
//                    progressResult.Progress = (int)(downloaded * 100 / this._chunks.Count);
//                    ProgressChanged?.Invoke(this, progressResult);
//                }
//            }
//        }

//        private bool IsFragmentedMP4()
//        {
//            var file = Helpers.GetFileName(_chunks.Last().Uri).ToLowerInvariant();
//            Log.Information("Checking fMp4 with: " + file);
//            return file.EndsWith(".m4s") || file.EndsWith(".m4v") || file.EndsWith(".m4a");
//        }

//        private int CreateFileList()
//        {
//            var files = new Dictionary<bool, List<string>>();
//            var fileNames = new HashSet<string>();
//            foreach (var chunk in this._chunks)
//            {
//                var file = _chunkStreamMap.GetStream(chunk.Id);
//                if (fileNames.Contains(file)) continue;
//                var lines = files.GetValueOrDefault(chunk.First, new List<string>());
//                lines.Add("file '" + file + "'" + Environment.NewLine);
//                files[chunk.First] = lines;
//                fileNames.Add(file);
//            }
//            foreach (var chunk in this._chunks)
//            {
//                var file = _chunkStreamMap.GetStream(chunk.Id);
//                if (fileNames.Contains(file)) continue;
//                var lines = files.GetValueOrDefault(chunk.First, new List<string>());
//                lines.Add("file '" + _chunkStreamMap.GetStream(chunk.Id) + "'" + Environment.NewLine);
//                files[chunk.First] = lines;
//                fileNames.Add(file);
//            }
//            File.WriteAllLines(Path.Combine(Config.DataDir, Id, "chunks-0.txt"), files[true]);
//            if (files.Count > 1)
//            {
//                File.WriteAllLines(Path.Combine(Config.DataDir, Id, "chunks-1.txt"), files[false]);
//            }
//            return files.Count;
//        }

//        private void ConcatSegments(IEnumerable<string> files, string target)
//        {
//            byte[] buf = new byte[512 * 1024];

//            using var fsout = new FileStream(target, FileMode.Create, FileAccess.ReadWrite);
//            foreach (string file in files)
//            {
//                using var infs = new FileStream(file, FileMode.Open, FileAccess.Read);
//                while (!this._cancellationTokenSource.IsCancellationRequested)
//                {
//                    var x = infs.Read(buf, 0, buf.Length);
//                    if (x == 0)
//                    {
//                        break;
//                    }
//                    fsout.Write(buf, 0, x);
//                }
//            }
//        }

//        private void Assemble(int count)
//        {
//            if (mediaProcessor != null)
//            {
//                if (string.IsNullOrWhiteSpace(this.TargetDir))
//                {
//                    this.TargetDir = Helpers.GetDownloadFolderByPath(this.TargetFileName);
//                }

//                mediaProcessor.ProgressChanged += (s, e) => this.AssembingProgressChanged.Invoke(this, e);

//                if (IsFragmentedMP4())
//                {
//                    if (this._state.PlayListContainer.AudioPlaylist == null)
//                    {
//                        ConcatSegments(this._chunks.Select(c => this._chunkStreamMap.GetStream(c.Id)), TargetFile);
//                    }
//                    else
//                    {

//                        ConcatSegments(this._chunks.Where(c => c.First).Select(c => this._chunkStreamMap.GetStream(c.Id)),
//                            Path.Combine(Config.DataDir, Id, "1_" + TargetFileName + ".mp4"));
//                        ConcatSegments(this._chunks.Where(c => !c.First).Select(c => this._chunkStreamMap.GetStream(c.Id)),
//                            Path.Combine(Config.DataDir, Id, "2_" + TargetFileName + ".mp4"));

//                        if (mediaProcessor.MergeAudioVideStream(Path.Combine(Config.DataDir, Id, "1_" + TargetFileName + ".mp4"),
//                        Path.Combine(Config.DataDir, Id, "2_" + TargetFileName + ".mp4"), TargetFile,
//                        this._cancellationTokenSource.Token) != MediaProcessingResult.Success)
//                        {
//                            throw new AssembleFailedException(); //TODO: Add more info about error
//                        }
//                    }
//                }
//                else
//                {
//                    if (mediaProcessor.MergeHLSAudioVideStream(Path.Combine(Config.DataDir, Id, "chunks-0.txt"),
//                    count == 1 ? TargetFile : Path.Combine(Config.DataDir, Id, "1_" + TargetFileName + ".ts"), this._cancellationTokenSource.Token)
//                    != MediaProcessingResult.Success)
//                    {
//                        throw new AssembleFailedException(); //TODO: Add more info about error
//                    }
//                    if (count > 1)
//                    {
//                        if (mediaProcessor.MergeHLSAudioVideStream(Path.Combine(Config.DataDir, Id, "chunks-1.txt"),
//                            Path.Combine(Config.DataDir, Id, "2_" + TargetFileName + ".ts"), this._cancellationTokenSource.Token)
//                            != MediaProcessingResult.Success)
//                        {
//                            throw new AssembleFailedException(); //TODO: Add more info about error
//                        }
//                        if (mediaProcessor.MergeAudioVideStream(Path.Combine(Config.DataDir, Id, "1_" + TargetFileName + ".ts"),
//                            Path.Combine(Config.DataDir, Id, "2_" + TargetFileName + ".ts"), TargetFile,
//                            this._cancellationTokenSource.Token) != MediaProcessingResult.Success)
//                        {
//                            throw new AssembleFailedException(); //TODO: Add more info about error
//                        }
//                    }
//                }
//            }
//            else
//            {
//                throw new AssembleFailedException(); //TODO: Add more info about error
//            }
//        }

//        public void SetFileName(string name, FileNameFetchMode fileNameFetchMode)
//        {
//            this.TargetFileName = name;
//            //this.fileNameFetchMode = fileNameFetchMode;
//        }

//        public void SetTargetDirectory(string folder)
//        {
//            this.TargetDir = folder;
//        }
//    }

//    internal class HlsDownloadState
//    {
//        public HlsPlaylistContainer PlayListContainer;
//        public string Id;
//        public Dictionary<string, List<string>> Headers;
//        public Dictionary<string, string> Cookies;
//        public long FileSize = -1;
//        public double Duration;
//        public string TempDirectory;
//        public bool IsEncrypted;
//    }

//    internal class HlsChunk : Chunk
//    {
//        public bool First { get; set; }
//        public double Duration { get; set; }
//        public Uri KeyUrl { get; set; }
//        public string IV { get; set; }
//    }
//}
