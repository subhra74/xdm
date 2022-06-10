using System;
using System.Collections.Generic;
using System.Linq;
using System.IO;
using System.Threading;
using XDM.Core.Lib.Util;
using XDM.Core.Lib.Common;
using TraceLog;
using XDM.Core.Lib.Clients.Http;
using XDM.Core.Lib.Common.MediaProcessor;

namespace XDM.Core.Lib.Downloader.Progressive.SingleHttp
{
    public class SingleSourceHTTPDownloader : HTTPDownloaderBase
    {
        private SingleSourceHTTPDownloaderState? state;
        private bool init;
        public override Uri? PrimaryUrl => this.state?.Url;

        public override int SpeedLimit => this.state?.SpeedLimit ?? 0;

        public override bool EnableSpeedLimit => this.state?.SpeedLimit > 0;

        public SingleSourceHTTPDownloader(SingleSourceHTTPDownloadInfo info, IHttpClient? hc = null,
            AuthenticationInfo? authentication = null, ProxyInfo? proxy = null, int speedLimit = 0,
            BaseMediaProcessor mediaProcessor = null, bool convertToMp3 = false)
        {
            Id = Guid.NewGuid().ToString();

            this.state = new SingleSourceHTTPDownloaderState
            {
                Url = new Uri(info.Uri),
                Id = this.Id,
                Cookies = info.Cookies,
                Headers = info.Headers,
                TempDir = Path.Combine(Config.Instance.TempDir, Id),
                Authentication = authentication,
                Proxy = proxy,
                SpeedLimit = speedLimit,
                ConvertToMp3 = convertToMp3
            };

            if (this.state.Headers == null)
            {
                this.CreateDefaultHeaders();
            }

            if (this.state.Authentication == null)
            {
                this.state.Authentication = Helpers.GetAuthenticationInfoFromConfig(this.state.Url);
            }

            this.TargetFileName = Helpers.SanitizeFileName(info.File);
            this.http = hc;
            this.mediaProcessor = mediaProcessor;
        }

        public SingleSourceHTTPDownloader(string id, IHttpClient? http = null,
            BaseMediaProcessor mediaProcessor = null)
        {
            Id = id;
            cancelFlag = new();
            this.http = http;
            this.mediaProcessor = mediaProcessor;
        }

        public void SetDownloadInfo(SingleSourceHTTPDownloadInfo info)
        {
            this.state!.Url = new Uri(info.Uri);
            this.state.Cookies = info.Cookies;
            this.state.Headers = info.Headers;
            this.SaveState();
        }

        public override void Start()
        {
            Start(true);
        }

        private void Start(bool start)
        {
            if (state?.TempDir == null)
            {
                throw new InvalidOperationException("Temp dir should not be null");
            }
            Directory.CreateDirectory(state.TempDir);
            downloadSizeAtResume = 0;
            ticksAtDownloadStartOrResume = Helpers.TickCount();
            var chunk = new Piece
            {
                Offset = 0,
                Length = -1,
                Downloaded = 0,
                State = SegmentState.NotStarted,
                Id = Guid.NewGuid().ToString()
            };
            pieces[chunk.Id] = chunk;
            init = false;
            grabberDict[chunk.Id] = new PieceGrabber(chunk.Id, this);
            SaveState();
            if (start)
            {
                Log.Debug("SingleSourceHTTPDownloader start");
                OnStarted();
                this.http ??= http = HttpClientFactory.NewHttpClient(state.Proxy);
                http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                grabberDict[chunk.Id].Download();
            }
        }

        public override void SaveForLater()
        {
            Start(false);
        }

        public override void Resume()
        {
            new Thread(() =>
            {
                try
                {
                    Log.Debug("SingleSourceHTTPDownloader Resume");
                    RestoreState();
                    Directory.CreateDirectory(state.TempDir);
                    if (pieces.Count != 0)
                    {
                        OnStarted();
                        Log.Debug("Chunks found: " + pieces.Count);
                        if (this.AllFinished())
                        {
                            this.AssemblePieces();
                            Console.WriteLine("Download finished");
                            base.OnFinished();
                            return;
                        }
                        else
                        {
                            this.http ??= HttpClientFactory.NewHttpClient(state.Proxy);
                            http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                            init = true;
                            CreatePiece();
                        }
                    }
                    else
                    {
                        Console.WriteLine("Starting new download");
                        Start();
                    }
                }
                catch (Exception e)
                {
                    Log.Debug(e, e.Message);
                    base.OnFailed(e is DownloadException de ? de.ErrorCode : ErrorCode.Generic);
                }
            }).Start();
        }

        protected override void SaveChunkState()
        {
            lock (this)
            {
                if (pieces.Count == 0) return;
                TransactedIO.WriteStream("chunks.db", state!.TempDir!, base.ChunkStateToBytes);
                //TransactedIO.WriteBytes(ChunkStateToBytes(), "chunks.db", state.TempDir);
            }
        }

        protected override void SaveState()
        {
            TransactedIO.WriteBytes(DownloadStateStore.StateToBytes(state), Id + ".state", Config.DataDir);
        }

        public override void RestoreState()
        {
            var bytes = TransactedIO.ReadBytes(Id + ".state", Config.DataDir);
            if (bytes == null)
            {
                throw new FileNotFoundException(Path.Combine(Config.DataDir, Id + ".state"));
            }
            state = DownloadStateStore.SingleSourceHTTPDownloaderStateFromBytes(bytes);
            try
            {
                //var chunkBytes = TransactedIO.ReadBytes("chunks.db", state.TempDir);
                //if (chunkBytes == null)
                //{
                //    throw new FileNotFoundException(Path.Combine(state.TempDir, "chunks.json"));
                //}
                //pieces = ChunkStateFromBytes(chunkBytes);
                if (!TransactedIO.ReadStream("chunks.db", state!.TempDir!, s =>
                {
                    pieces = ChunkStateFromBytes(s);
                }))
                {
                    throw new FileNotFoundException(Path.Combine(state.TempDir, "chunks.db"));
                }
                Log.Debug("Total size: " + state.FileSize);
                //foreach (var item in pieces.Keys)
                //{
                //    Log.Debug("Chunk id: " + item + " offset: " + pieces[item].Offset + " downloaded: " +
                //        pieces[item].Downloaded + " length: " + pieces[item].Length + " state: " + pieces[item].State);
                //}
            }
            catch
            {
                // ignored
                Console.WriteLine("Chunk restore failed");
            }
            TicksAndSizeAtResume();
        }

        public override bool IsFirstRequest(StreamType streamType)
        {
            return !init;
        }

        public override void PieceConnected(string pieceId, ProbeResult? result)
        {
            lock (this)
            {
                if (this.cancelFlag.IsCancellationRequested) return;
                if (result != null) //probe result is not null for first request only, for subsequent requests its always null
                {
                    Log.Debug("connected: " + result.ResourceSize + " init...");
                    state.LastModified = result.LastModified;
                    this.totalSize = result.ResourceSize ?? -1;
                    this.resumable = result.Resumable;
                    var piece = this.pieces[pieceId];
                    piece.Length = result.ResourceSize ?? -1;
                    piece.Offset = 0;
                    piece.Downloaded = 0;
                    piece.State = SegmentState.NotStarted;
                    this.init = true;

                    Log.Debug("fileNameFetchMode: " + fileNameFetchMode);
                    Log.Debug("Attachment: " + result.AttachmentName);
                    Log.Debug("ContentType: " + result.ContentType);
                    switch (fileNameFetchMode)
                    {
                        case FileNameFetchMode.FileNameAndExtension:
                            if (result.AttachmentName != null)
                            {
                                this.TargetFileName = Helpers.SanitizeFileName(result.AttachmentName);
                            }
                            else
                            {
                                this.TargetFileName = Helpers.GetFileName(
                                        result.FinalUri, result.ContentType);
                            }
                            break;
                        case FileNameFetchMode.ExtensionOnly:
                            var name = string.Empty;
                            if (Helpers.AddFileExtension(this.TargetFileName, result.ContentType, out name))
                            {
                                this.TargetFileName = name;
                            }
                            break;
                    }

                    state.Url = result.FinalUri;
                    state.FileSize = result.ResourceSize ?? -1;
                    SaveState();
                    SaveChunkState();
                    base.OnProbed();

                    //check if required disk space is available
                    if (result.ResourceSize.HasValue &&
                        result.ResourceSize.Value > 0 &&
                        Helpers.GetFreeSpace(this.state.TempDir, out long freespace) &&
                        freespace < result.ResourceSize.Value)
                    {
                        throw new AssembleFailedException(ErrorCode.DiskError);
                    }
                }
                CreatePiece();
            }
        }

        private List<Piece> SortAndValidatePieces()
        {
            var pieces = this.pieces.Select(p => p.Value).ToList();
            pieces.Sort((a, b) =>
            {
                var diff = a.Offset - b.Offset;
                if (diff == 0) return 0;
                return diff > 0 ? 1 : -1;
            });
            if (this.cancelFlag.IsCancellationRequested) return null;
            if (string.IsNullOrEmpty(this.TargetDir))
            {
                this.TargetDir = Helpers.GetDownloadFolderByFileName(this.TargetFileName);
            }
            if (!Directory.Exists(this.TargetDir))
            {
                Directory.CreateDirectory(this.TargetDir);
            }
            if (Config.Instance.FileConflictResolution == FileConflictResolution.AutoRename)
            {
                this.TargetFileName = Helpers.GetUniqueFileName(this.TargetFileName, this.TargetDir);
            }

            //check if required disk space is available
            if (Helpers.GetFreeSpace(this.TargetDir, out long freespace))
            {
                if (freespace < FileSize)
                {
                    throw new AssembleFailedException(ErrorCode.DiskError);
                }
            }

            return pieces;
        }

        protected override void AssemblePieces()
        {
            Log.Debug("Assembling...");
            lock (this)
            {

                try
                {
                    var pieces = SortAndValidatePieces();
                    if (this.cancelFlag.IsCancellationRequested) return;

                    var totalBytes = 0L;

#if NET35
                    var buf = new byte[5 * 1024 * 1024];
#else
                    var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(5 * 1024 * 1024);
#endif


                    var outFile = state!.ConvertToMp3 ? Path.Combine(this.GetState().TempDir!, Guid.NewGuid().ToString())
                        : this.TargetFile;
                    using var outfs = new FileStream(outFile!, FileMode.Create, FileAccess.Write);
                    try
                    {
                        foreach (var pc in pieces)
                        {
                            if (this.cancelFlag.IsCancellationRequested) return;
                            using var infs = new FileStream(GetPieceFile(pc.Id), FileMode.Open, FileAccess.Read);
                            var len = pc.Length;
                            if (this.FileSize < 1)
                            {
                                while (!this.cancelFlag.IsCancellationRequested)
                                {
                                    var x = infs.Read(buf, 0, buf.Length);
                                    if (x == 0)
                                    {
                                        break;
                                    }
                                    try
                                    {
                                        outfs.Write(buf, 0, x);
                                    }
                                    catch (IOException ioe)
                                    {
                                        Log.Debug(ioe, "AssemblePieces");
                                        throw new AssembleFailedException(ErrorCode.DiskError, ioe);
                                    }
                                    totalBytes += x;
                                }
                            }
                            else
                            {
                                while (len > 0)
                                {
                                    if (this.cancelFlag.IsCancellationRequested) return;
                                    var x = infs.Read(buf, 0, (int)Math.Min(buf.Length, len));
                                    if (x == 0)
                                    {
                                        Log.Debug("EOF :: File corrupted");
                                        throw new Exception("EOF :: File corrupted");
                                    }
                                    try
                                    {
                                        outfs.Write(buf, 0, x);
                                    }
                                    catch (IOException ioe)
                                    {
                                        Log.Debug(ioe, "AssemblePieces");
                                        throw new AssembleFailedException(ErrorCode.DiskError, ioe);
                                    }
                                    len -= x;
                                    totalBytes += x;
                                    var prg = (int)(totalBytes * 100 / FileSize);
                                    if (state!.ConvertToMp3) prg /= 2;
                                    this.OnAssembleProgressChanged(prg);
                                }
                            }
                        }

                        if (this.cancelFlag.IsCancellationRequested) return;

                        if (state!.ConvertToMp3)
                        {
                            if (mediaProcessor != null)
                            {
                                mediaProcessor.ProgressChanged += (s, e) =>
                                {
                                    var prg = 50 + e.Progress / 2;
                                    if (prg > 100) prg = 100;
                                    this.OnAssembleProgressChanged(prg);
                                };
                                var res = mediaProcessor.ConvertToMp3Audio(outFile!, TargetFile!,
                                    this.cancelFlag, out totalBytes);
                                if (res != MediaProcessingResult.Success)
                                {
                                    throw new AssembleFailedException(
                                        res == MediaProcessingResult.AppNotFound ? ErrorCode.FFmpegNotFound :
                                        ErrorCode.FFmpegError); //TODO: Add more info about error
                                }

                                if (Config.Instance.FetchServerTimeStamp)
                                {
                                    try
                                    {
                                        File.SetLastWriteTime(TargetFile, state.LastModified);
                                    }
                                    catch { }
                                }
                                this.totalSize = totalBytes;
                            }
                            else
                            {
                                throw new AssembleFailedException(ErrorCode.Generic); //TODO: Add more info about error
                            }
                        }
                    }
                    finally
                    {
#if !NET35
                            System.Buffers.ArrayPool<byte>.Shared.Return(buf);
#endif
                    }

                    if (this.cancelFlag.IsCancellationRequested) return;

                    //Console.WriteLine("Total bytes written: {0} total size: {1}", totalBytes, this.totalSize);
                    if (this.totalSize < 1)
                    {
                        this.totalSize = totalBytes;
                    }
                    if (Config.Instance.FetchServerTimeStamp)
                    {
                        try
                        {
                            File.SetLastWriteTime(TargetFile, state.LastModified);
                        }
                        catch { }
                    }
                    if (this.cancelFlag.IsCancellationRequested) return;
                    Log.Debug("Deleting file parts");
                    DeleteFileParts();
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error in AssemblePieces");
                    throw new AssembleFailedException(ex is DownloadException de ? de.ErrorCode : ErrorCode.Generic);
                }
            }
        }

        public override (Dictionary<string, List<string>> Headers,
            Dictionary<string, string> Cookies,
            Uri Url, AuthenticationInfo? Authentication,
            ProxyInfo? Proxy)?
            GetHeaderUrlAndCookies(string pieceId)
        {
            if (this.grabberDict.ContainsKey(pieceId) && pieces.ContainsKey(pieceId))
            {
                return (this.state.Headers, this.state.Cookies, this.state.Url,
                    this.state.Authentication
                    , this.state.Proxy);
            }
            return null;
        }

        protected override BaseHTTPDownloaderState GetState()
        {
            return this.state;
        }

        public override void ThrottleIfNeeded()
        {
            base.ThrottleIfNeeded(this.state!);
        }

        public override bool IsTextRedirectionAllowed() { return false; }

        public override bool IsFileChangedOnServer(StreamType streamType, long streamSize, DateTime? lastModified)
        {
            if (state!.FileSize > 0)
            {
                return state!.FileSize != streamSize;
            }
            return false;
        }

        public override void UpdateSpeedLimit(bool enable, int limit)
        {
            base.UpdateSpeedLimit(this.state, enable, limit);
        }

        private void CreateDefaultHeaders()
        {
            this.state!.Headers = new Dictionary<string, List<string>>
            {
                ["User-Agent"] = new() { Config.Instance.FallbackUserAgent },
                ["Accept"] = new() { "*/*", },
                ["Accept-Encoding"] = new() { "identity", },
                ["Accept-Language"] = new() { "en-US", },
                ["Accept-Charset"] = new() { "*", },
                ["Referer"] = new() { new Uri(this.state.Url, ".").ToString() }
            };
        }
    }

    public class SingleSourceHTTPDownloaderState : BaseHTTPDownloaderState
    {
        public Uri Url;
        public Dictionary<string, List<string>> Headers;
        public Dictionary<string, string> Cookies;
        public bool ConvertToMp3;
    }
}
