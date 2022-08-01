using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using TraceLog;
using XDM.Core;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;
using XDM.Core.Clients.Http;
using XDM.Core.IO;
#if NET35
using XDM.Compatibility;
#endif
using System.Text;

namespace XDM.Core.Downloader.Adaptive
{
    public abstract class MultiSourceDownloaderBase : IBaseDownloader
    {
        protected IHttpClient _http;
        protected MultiSourceDownloadState _state;
        protected List<MultiSourceChunk> _chunks;
        protected CancelFlag _cancellationTokenSource;
        protected CancelFlag _cancellationTokenSourceStateSaver;
        protected SimpleStreamMap _chunkStreamMap;
        protected ICancelRequster _cancelRequestor;
        protected FileNameFetchMode _fileNameFetchMode = FileNameFetchMode.FileNameAndExtension;
        protected long lastUpdated = Helpers.TickCount();
        protected readonly ProgressResultEventArgs progressResult;
        protected SpeedLimiter speedLimiter = new();
        protected CountdownLatch? countdownLatch;
        public bool IsCancelled => _cancellationTokenSource.IsCancellationRequested;
        public string Id { get; private set; }
        public virtual long FileSize => this._state.FileSize;
        public virtual double Duration => this._state.Duration;
        protected ReaderWriterLockSlim rwLock = new(LockRecursionPolicy.SupportsRecursion);
        public ReaderWriterLockSlim Lock => this.rwLock;
        public FileNameFetchMode FileNameFetchMode
        {
            get { return _fileNameFetchMode; }
            set { _fileNameFetchMode = value; }
        }
        public virtual string TargetFile => Path.Combine(TargetDir, TargetFileName);
        public virtual string TargetFileName { get; set; }
        public virtual string TargetDir { get; set; }
        public virtual string Type => "N/A";
        public virtual Uri PrimaryUrl => null;

        public int SpeedLimit => _state?.SpeedLimit ?? 0;

        public bool EnableSpeedLimit => _state?.SpeedLimit > 0;

        public virtual event EventHandler Probed;
        public virtual event EventHandler Finished;
        public virtual event EventHandler Started;
        public virtual event EventHandler<ProgressResultEventArgs> ProgressChanged;
        public virtual event EventHandler Cancelled;
        public virtual event EventHandler<DownloadFailedEventArgs> Failed;
        public virtual event EventHandler<ProgressResultEventArgs> AssembingProgressChanged;
        protected BaseMediaProcessor mediaProcessor;
        protected long totalDownloadedBytes = 0L;
        protected long downloadedBytesSinceStartOrResume = 0L;
        protected int lastProgress = 0;
        protected long lastDownloaded = 0;
        protected long ticksAtDownloadStartOrResume = 0L;
        private bool stopRequested = false;

        public MultiSourceDownloaderBase(MultiSourceDownloadInfo info,
            IHttpClient? http = null,
            BaseMediaProcessor? mediaProcessor = null)
        {
            Id = Guid.NewGuid().ToString();

            _cancellationTokenSource = new();
            _cancellationTokenSourceStateSaver = new();

            progressResult = new ProgressResultEventArgs();

            this.mediaProcessor = mediaProcessor;
            this._http = http;

            _cancelRequestor = new CancelRequestor(_cancellationTokenSource);
            _chunks = new List<MultiSourceChunk>();
            _chunkStreamMap = new SimpleStreamMap
            {
                StreamMap = new Dictionary<string, string>()
            };
        }

        public MultiSourceDownloaderBase(string id,
            IHttpClient? http = null,
            BaseMediaProcessor? mediaProcessor = null)
        {
            Id = id;

            _cancellationTokenSource = new();
            _cancellationTokenSourceStateSaver = new();
            progressResult = new ProgressResultEventArgs();
            this.mediaProcessor = mediaProcessor;
            this._http = http;
            _cancelRequestor = new CancelRequestor(_cancellationTokenSource);
            _chunks = new List<MultiSourceChunk>();
            _chunkStreamMap = new SimpleStreamMap
            {
                StreamMap = new Dictionary<string, string>()
            };
        }

        protected abstract void SaveState();
        protected abstract void RestoreState();
        protected abstract void Init(string tempDir);
        protected abstract void OnContentTypeReceived(Chunk chunk, string contentType);

        public virtual void Start()
        {
            Start(true);
        }

        private void Start(bool start)
        {
            new Thread(() =>
            {
                Directory.CreateDirectory(_state.TempDirectory);
                ticksAtDownloadStartOrResume = Helpers.TickCount();
                SaveState();
                if (start)
                {
                    Started?.Invoke(this, EventArgs.Empty);
                    Download();
                }
            }).Start();
        }

        public void SaveForLater()
        {
            Start(false);
        }

        public virtual void Stop()
        {
            if (stopRequested)
            {
                return;
            }
            stopRequested = true;
            _cancellationTokenSourceStateSaver.Cancel();
            _cancellationTokenSource.Cancel();
            _cancelRequestor?.CancelAll();
            speedLimiter.WakeIfSleeping();
            //try { this.semaphore?.Release(); } catch { }
            try { this.countdownLatch?.Break(); } catch { }
            try
            {
                SaveChunkState();
                _http.Dispose();
                Log.Debug("Stopped");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error while disposing http client");
            }
        }

        public virtual void Resume()
        {
            new Thread(() =>
            {
                try
                {
                    Started?.Invoke(this, EventArgs.Empty);
                    RestoreState();
                    Directory.CreateDirectory(_state.TempDirectory);

                    if (_chunks == null)
                    {
                        Log.Debug("Chunk restore failed");
                        Download();
                        return;
                    }
                    this._http ??= HttpClientFactory.NewHttpClient(_state.Proxy);
                    this._http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);

                    DownloadChunks();

                    this._cancellationTokenSource.ThrowIfCancellationRequested();

                    Assemble();
                    OnComplete();
                }
                catch (OperationCanceledException ex)
                {
                    Log.Debug(ex, ex.Message);
                    if (this._cancelRequestor.Error != ErrorCode.None)
                    {
                        OnFailed(new DownloadFailedEventArgs(this._cancelRequestor.Error));
                    }
                    OnCancelled();
                }
                catch (FileNotFoundException ex)
                {
                    Log.Debug(ex, ex.Message);
                    OnFailed(new DownloadFailedEventArgs(ErrorCode.FFmpegNotFound));
                }
                //catch (HttpException ex)
                //{
                //    Log.Error(ex, ex.Message);
                //    OnFailed(new DownloadFailedEventArgs(ErrorCode.InvalidResponse));
                //}
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                    if (ex.InnerException is HttpException he)
                    {
                        OnFailed(new DownloadFailedEventArgs(ErrorCode.InvalidResponse));
                    }
                    else
                    {
                        OnFailed(new DownloadFailedEventArgs(
                            ex is DownloadException de ? de.ErrorCode : ErrorCode.Generic));
                    }
                }
            }).Start();
        }

        protected virtual void SaveChunkState()
        {
            if (_chunks == null) return;
            try
            {
                rwLock.EnterWriteLock();
                TransactedIO.WriteStream("chunks.db", _state.TempDirectory, ChunkStateToBytes);
            }
            finally
            {
                rwLock.ExitWriteLock();
            }
        }

        private void Download()
        {
            try
            {
                this._http ??= HttpClientFactory.NewHttpClient(_state.Proxy);
                this._http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                //this._http.DefaultRequestVersion = HttpVersion.Version20;
                //this._http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                //SetHeaders(_http, _state.Headers);
                //SetCookies(_http, _state.Cookies);
                //if (_state.Authentication != null)
                //{
                //    SetAuthentication(_http, _state.Authentication);
                //}

                Directory.CreateDirectory(_state.TempDirectory);

                Init(_state.TempDirectory);
                SaveState();
                OnProbe();
                DownloadChunks();

                if (_cancellationTokenSource.IsCancellationRequested)
                {
                    throw new OperationCanceledException();
                }

                Assemble();
                OnComplete();
            }
            catch (OperationCanceledException ex)
            {
                Console.WriteLine(ex);
                OnCancelled();
            }
            //catch (HttpException ex)
            //{
            //    Console.WriteLine(ex);
            //    OnFailed(new DownloadFailedEventArgs(ErrorCode.InvalidResponse));
            //}
            catch (Exception ex)
            {
                Console.WriteLine(ex);
                if (ex.InnerException is HttpException)
                {
                    var he = ex.InnerException as HttpException;
                    OnFailed(new DownloadFailedEventArgs(ErrorCode.InvalidResponse));
                }
                else
                {
                    OnFailed(new DownloadFailedEventArgs(
                        ex is DownloadException de ? de.ErrorCode : ErrorCode.Generic));
                }
            }
        }

        protected void OnProbe()
        {
            var probeEventHandler = Probed;
            probeEventHandler?.Invoke(this, EventArgs.Empty);
        }

        private void DownloadChunkRange(int startIndex, int endIndex, CountdownLatch latch)
        {
            Log.Debug("Starting thread for range: " + startIndex + " -> " + endIndex);

            var count = 0;
            new Thread(() =>
            {
                Log.Debug("Inside thread");
                for (var i = startIndex; i <= endIndex; i++)
                {
                    if (this._cancellationTokenSource.IsCancellationRequested)
                    {
                        break;
                    }
                    var chunk = _chunks[i];
                    if (chunk.ChunkState == ChunkState.Finished)
                    {
                        continue;
                    }
                    DownloadChunk(chunk, latch);
                    count++;
                }
                Log.Debug("Finished chunk-count: " + count);
            }).Start();
        }

        private void DownloadChunks()
        {
            var unfinishedPieceCount = _chunks.Where(chunk => chunk.ChunkState != ChunkState.Finished).Count();
            if (unfinishedPieceCount < 1) return;
            SaveChunkState();
            this.countdownLatch = new(unfinishedPieceCount);

            Log.Debug("Downloading chunks: " + unfinishedPieceCount);

            var threadCount = Math.Min(unfinishedPieceCount, Config.Instance.MaxSegments);
            var piecePerThread = (int)Math.Ceiling((float)unfinishedPieceCount / threadCount);
            var startIndex = 0;
            var endIndex = 0;

            var m = 0;
            for (var i = 0; i < _chunks.Count; i++)
            {
                var chunk = _chunks[i];
                if (chunk.ChunkState != ChunkState.Finished) m++;
                if (m == piecePerThread)
                {
                    endIndex = i;
                    DownloadChunkRange(startIndex, endIndex, countdownLatch);
                    startIndex = i + 1;
                    m = 0;
                }
            }
            if (m != 0)
            {
                endIndex = _chunks.Count - 1;
                DownloadChunkRange(startIndex, endIndex, countdownLatch);
            }

            Log.Debug("Waiting for downloading all chunks");
            this.countdownLatch.Wait();
            SaveChunkState();
            _cancellationTokenSourceStateSaver.Cancel();
            Log.Debug("Countdown latch exited");
        }

        private void DownloadChunk(Chunk chunk, CountdownLatch latch)
        {
            var chunkDownloader = new HttpChunkDownloader(chunk, _http, this._state.Headers,
                this._state.Cookies, this._state.Authentication,
                _chunkStreamMap, _cancelRequestor);

            try
            {
                chunkDownloader.ChunkDataReceived += ChunkDataReceived;
                chunkDownloader.MimeTypeReceived += MimeTypeReceived;
                chunkDownloader.Download();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            finally
            {
                chunkDownloader.ChunkDataReceived -= ChunkDataReceived;
                chunkDownloader.MimeTypeReceived -= MimeTypeReceived;
                latch.CountDown();
            }
        }

        private void ChunkDataReceived(object sender, ChunkDownloadedEventArgs args)
        {
            try
            {
                rwLock.EnterWriteLock();
                long tick = Helpers.TickCount();
                totalDownloadedBytes += args.Downloaded;
                downloadedBytesSinceStartOrResume += args.Downloaded;
                var ticksElapsed = tick - lastUpdated;
                if (ticksElapsed >= 2000)
                {
                    var downloadedCount = _chunks.FindAll(c => c.ChunkState == ChunkState.Finished).Count;
                    progressResult.Progress = (int)(downloadedCount * 100 / this._chunks.Count);
                    progressResult.Downloaded = totalDownloadedBytes;
                    var prgDiff = progressResult.Progress - lastProgress;
                    lastProgress = progressResult.Progress;
                    if (prgDiff > 0)
                    {
                        var eta = (ticksElapsed * (100 - progressResult.Progress) / 1000 * prgDiff);
                        progressResult.Eta = eta;
                    }
                    var timeDiff = tick - ticksAtDownloadStartOrResume;
                    if (timeDiff > 0)
                    {
                        progressResult.DownloadSpeed = (downloadedBytesSinceStartOrResume * 1000.0) / timeDiff;
                    }
                    lastUpdated = tick;
                    ProgressChanged?.Invoke(this, progressResult);
                    SaveChunkState();
                }
                this.ThrottleIfNeeded();
            }
            finally
            {
                rwLock.ExitWriteLock();
            }
        }

        private void MimeTypeReceived(object sender, MimeTypeReceivedEventArgs args)
        {
            try
            {
                rwLock.EnterWriteLock();
                this.OnContentTypeReceived(args.Chunk, args.MimeType);
            }
            finally
            {
                rwLock.ExitWriteLock();
            }
        }

        //private int CreateFileList()
        //{
        //    var files = new Dictionary<bool, List<string>>();
        //    var fileNames = new HashSet<string>();
        //    foreach (var chunk in this._chunks)
        //    {
        //        var file = _chunkStreamMap.GetStream(chunk.Id);
        //        if (fileNames.Contains(file)) continue;
        //        var lines = files.GetValueOrDefault(chunk.First, new List<string>());
        //        lines.Add("file '" + file + "'" + Environment.NewLine);
        //        files[chunk.First] = lines;
        //        fileNames.Add(file);
        //    }
        //    foreach (var chunk in this._chunks)
        //    {
        //        var file = _chunkStreamMap.GetStream(chunk.Id);
        //        if (fileNames.Contains(file)) continue;
        //        var lines = files.GetValueOrDefault(chunk.First, new List<string>());
        //        lines.Add("file '" + _chunkStreamMap.GetStream(chunk.Id) + "'" + Environment.NewLine);
        //        files[chunk.First] = lines;
        //        fileNames.Add(file);
        //    }
        //    File.WriteAllLines(Path.Combine(Config.DataDir, Id, "chunks-0.txt"), files[true]);
        //    if (files.Count > 1)
        //    {
        //        File.WriteAllLines(Path.Combine(Config.DataDir, Id, "chunks-1.txt"), files[false]);
        //    }
        //    return files.Count;
        //}

        private void ConcatSegments(IEnumerable<string> files, string target)
        {
#if NET35
            var buf = new byte[5 * 1024 * 1024];
#else
            var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(5 * 1024 * 1024);
#endif

            try
            {
                var totalSize = 0L;
                using var fsout = new FileStream(target, FileMode.Create, FileAccess.ReadWrite);
                foreach (string file in files)
                {
                    using var infs = new FileStream(file, FileMode.Open, FileAccess.Read);
                    while (!this._cancellationTokenSource.IsCancellationRequested)
                    {
                        var x = infs.Read(buf, 0, buf.Length);
                        if (x == 0)
                        {
                            break;
                        }
                        try
                        {
                            fsout.Write(buf, 0, x);
                        }
                        catch (IOException ioe)
                        {
                            throw new AssembleFailedException(ErrorCode.DiskError, ioe);
                        }
                        totalSize += x;
                    }
                }
                this._state.FileSize = totalSize;
            }
            finally
            {
#if !NET35
                System.Buffers.ArrayPool<byte>.Shared.Return(buf);
#endif
            }
        }

        protected virtual void Assemble()
        {
            SaveChunkState();
            if (this._cancellationTokenSource.IsCancellationRequested) return;
            if (string.IsNullOrEmpty(this.TargetDir))
            {
                this.TargetDir = FileHelper.GetDownloadFolderByFileName(this.TargetFileName);
            }

            if (!Directory.Exists(this.TargetDir))
            {
                Directory.CreateDirectory(this.TargetDir);
            }

            if (Config.Instance.FileConflictResolution == FileConflictResolution.AutoRename)
            {
                this.TargetFileName = FileHelper.GetUniqueFileName(this.TargetFileName, this.TargetDir);
            }

            if (!_state.Demuxed)
            {
                ConcatSegments(this._chunks.Select(c => this._chunkStreamMap.GetStream(c.Id)), TargetFile);
                if (this._cancellationTokenSource.IsCancellationRequested) return;
                DeleteFileParts();
                return;
            }

            if (mediaProcessor == null)
            {
                throw new AssembleFailedException(ErrorCode.Generic); //TODO: Add more info about error
            }

            mediaProcessor.ProgressChanged += (s, e) => this.AssembingProgressChanged.Invoke(this, e);

            var videoFile = Path.Combine(_state.TempDirectory, "1_" + TargetFileName + _state.VideoContainerFormat);
            var audioFile = Path.Combine(_state.TempDirectory, "2_" + TargetFileName + _state.AudioContainerFormat);

            ConcatSegments(this._chunks.Where(c => c.StreamIndex == 0).Select(c => this._chunkStreamMap.GetStream(c.Id)),
                            videoFile);
            ConcatSegments(this._chunks.Where(c => c.StreamIndex == 1).Select(c => this._chunkStreamMap.GetStream(c.Id)),
                audioFile);
            if (this._cancellationTokenSource.IsCancellationRequested) return;

            var res = mediaProcessor.MergeAudioVideStream(videoFile, audioFile, TargetFile,
                this._cancellationTokenSource, out long totalSize);
            if (this._cancellationTokenSource.IsCancellationRequested) return;
            if (res != MediaProcessingResult.Success)
            {
                //try with matroska container
                var name = Path.GetFileNameWithoutExtension(TargetFileName);
                TargetFileName = name + ".mkv";
                this.TargetFileName = FileHelper.GetUniqueFileName(this.TargetFileName, this.TargetDir);
                if (mediaProcessor.MergeAudioVideStream(videoFile, audioFile, TargetFile,
                this._cancellationTokenSource, out totalSize) != MediaProcessingResult.Success)
                {
                    //try with matroska container
                    throw new AssembleFailedException(
                        res == MediaProcessingResult.AppNotFound ? ErrorCode.FFmpegNotFound :
                                ErrorCode.FFmpegError); //TODO: Add more info about error
                }
            }

            if (this._cancellationTokenSource.IsCancellationRequested) return;
            DeleteFileParts();

            this._state.FileSize = totalSize;
        }

        private void DeleteFileParts()
        {
            Log.Debug("DeleteFileParts...");
            try
            {
                Directory.Delete(_state.TempDirectory, true);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "DeleteFileParts");
            }
        }

        public void SetFileName(string name, FileNameFetchMode fileNameFetchMode)
        {
            this.TargetFileName = FileHelper.SanitizeFileName(name);
            //this.fileNameFetchMode = fileNameFetchMode;
        }

        public void SetTargetDirectory(string folder)
        {
            this.TargetDir = folder;
        }

        //private static void SetHeaders(System.Net.Http.HttpClient _http, Dictionary<string, List<string>> Headers)
        //{
        //    if (Headers != null)
        //    {
        //        foreach (var e in Headers)
        //        {
        //            try
        //            {
        //                _http.DefaultRequestHeaders.Add(e.Key, e.Value);
        //            }
        //            catch (Exception ex)
        //            {
        //                Log.Error(ex, "Setting headers failed in MultiSourceDownloaderBase");
        //            }
        //        }
        //    }
        //}

        //private static void SetCookies(System.Net.Http.HttpClient _http, Dictionary<string, string> cookies)
        //{
        //    if (cookies != null)
        //    {
        //        try
        //        {
        //            _http.DefaultRequestHeaders.Add("Cookie",
        //            string.Join(";", cookies.Select(kv => kv.Key + "=" + kv.Value).ToList()));
        //        }
        //        catch (Exception ex)
        //        {
        //            Log.Error(ex, "Setting cookies failed in MultiSourceDownloaderBase");
        //        }
        //    }
        //}

        //private void SetAuthentication(System.Net.Http.HttpClient _http, AuthenticationInfo? authentication)
        //{
        //    if (!authentication.HasValue)
        //    {
        //        return;
        //    }
        //    try
        //    {
        //        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
        //            Convert.ToBase64String(Encoding.UTF8.GetBytes(authentication.Value.UserName + ":" + authentication.Value.Password)));
        //    }
        //    catch (Exception ex)
        //    {
        //        Log.Error(ex, "Error setting auth header value");
        //    }
        //}

        protected void OnComplete()
        {
            Log.Debug("OnComplete");
            Finished?.Invoke(this, EventArgs.Empty);
            Cleanup();
        }

        protected void OnFailed(DownloadFailedEventArgs args)
        {
            if (args.ErrorCode == ErrorCode.InvalidResponse && totalDownloadedBytes > 0)
            {
                Failed?.Invoke(this, new DownloadFailedEventArgs(ErrorCode.SessionExpired));
            }
            else
            {
                Failed?.Invoke(this, args);
            }
            Cleanup();
        }

        protected void OnCancelled()
        {
            Cancelled?.Invoke(this, EventArgs.Empty);
            Cleanup();
        }

        private void Cleanup()
        {
            try
            {
                this._http?.Dispose();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Exception while disposing http client");
            }
        }

        public long GetTotalDownloaded() => this.totalDownloadedBytes;

        public long GetDownloaded() => this.downloadedBytesSinceStartOrResume;

        protected void ThrottleIfNeeded()
        {
            speedLimiter.ThrottleIfNeeded(this);
        }

        protected static void WriteChunkState(List<MultiSourceChunk> chunks, BinaryWriter w)
        {
            w.Write(chunks.Count);
            foreach (var chunk in chunks)
            {
                w.Write(chunk.Id);
                w.Write(chunk.Downloaded);
                w.Write(chunk.Size);
                w.Write(chunk.Offset);
                w.Write(chunk.StreamIndex);
                w.Write(chunk.Duration);
                w.Write((int)chunk.ChunkState);
                w.Write(chunk.Uri.ToString());
            }
        }

        protected static void ReadChunkState(BinaryReader r, out List<MultiSourceChunk> chunks)
        {
            var count = r.ReadInt32();
            chunks = new(count);
            for (var i = 0; i < count; i++)
            {
                chunks.Add(new MultiSourceChunk
                {
                    Id = r.ReadString(),
                    Downloaded = r.ReadInt64(),
                    Size = r.ReadInt64(),
                    Offset = r.ReadInt64(),
                    StreamIndex = r.ReadInt32(),
                    Duration = r.ReadDouble(),
                    ChunkState = (ChunkState)r.ReadInt32(),
                    Uri = new Uri(r.ReadString())
                });
            }
        }

        protected List<MultiSourceChunk> ChunkStateFromBytes(Stream stream)
        {
#if NET35
            var ms = new MemoryStream();
            stream.CopyTo(ms);
            using var r = new BinaryReader(ms);
#else
            using var r = new BinaryReader(stream, Encoding.UTF8, true);
#endif
            ReadChunkState(r, out List<MultiSourceChunk> chunks);
            return chunks;
        }

        protected void ChunkStateToBytes(Stream stream)
        {
#if NET35
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms, Encoding.UTF8);
            WriteChunkState(_chunks, w);
            ms.CopyTo(stream);
#else
            using var w = new BinaryWriter(stream, Encoding.UTF8, true);
            WriteChunkState(_chunks, w);
#endif
        }

        public void UpdateSpeedLimit(bool enable, int limit)
        {
            try
            {
                rwLock.EnterWriteLock();
                if (!enable)
                {
                    limit = 0;
                }
                _state.SpeedLimit = limit;
                SaveState();
            }
            finally
            {
                rwLock.ExitWriteLock();
            }
        }
    }

    public class MultiSourceChunk : Chunk
    {
        public int StreamIndex { get; set; }
        public double Duration { get; set; }
    }

    public abstract class MultiSourceDownloadInfo : IRequestData
    {
        public Dictionary<string, string> Cookies { get; set; }
        public Dictionary<string, List<string>> Headers { get; set; }
        public string File { get; set; }
        public string ContentType { get; set; }
    }

    public abstract class MultiSourceDownloadState
    {
        public string Id;
        public Dictionary<string, List<string>> Headers;
        public Dictionary<string, string> Cookies;
        public long FileSize = -1;
        public double Duration;
        public string TempDirectory;
        public bool Demuxed;
        public int AudioChunkCount = 0;
        public int VideoChunkCount = 0;
        /// <summary>
        /// Container file extension
        /// </summary>
        public string VideoContainerFormat = "";
        /// <summary>
        /// Container file extension
        /// </summary>
        public string AudioContainerFormat = "";


        public AuthenticationInfo? Authentication;
        public ProxyInfo? Proxy;
        public int SpeedLimit;
    }
}
