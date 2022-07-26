using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using TraceLog;
using XDM.Core;
using XDM.Core.Clients.Http;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;

namespace XDM.Core.Downloader.Progressive.DualHttp
{
    public class DualSourceHTTPDownloader : HTTPDownloaderBase
    {
        private DualSourceHTTPDownloaderState state;
        public override string Type => "Dash";
        public override Uri PrimaryUrl => this.state?.Url1 ?? this.state?.Url2;
        public override int SpeedLimit => this.state?.SpeedLimit ?? 0;
        public override bool EnableSpeedLimit => this.state?.SpeedLimit > 0;

        public DualSourceHTTPDownloader(DualSourceHTTPDownloadInfo info, IHttpClient hc = null,
            AuthenticationInfo? authentication = null, ProxyInfo? proxy = null, int speedLimit = 0,
            BaseMediaProcessor mediaProcessor = null)
        {
            Id = Guid.NewGuid().ToString();

            var uri1 = new Uri(info.Uri1);
            var uri2 = new Uri(info.Uri2);
            this.state = new DualSourceHTTPDownloaderState
            {
                Url1 = uri1,
                Url2 = uri2,
                Id = Id,
                Cookies1 = info.Cookies1,
                Cookies2 = info.Cookies2,
                Headers1 = info.Headers1,
                Headers2 = info.Headers2,
                TempDir = Path.Combine(Config.Instance.TempDir, Id),
                Authentication = authentication,
                Proxy = proxy,
                SpeedLimit = speedLimit
            };

            if (this.state.Authentication == null)
            {
                this.state.Authentication = Helpers.GetAuthenticationInfoFromConfig(this.state.Url1);
            }

            this.http = hc;
            this.TargetFileName = FileHelper.SanitizeFileName(info.File);
            this.mediaProcessor = mediaProcessor;
        }

        public DualSourceHTTPDownloader(string id, IHttpClient http = null,
            BaseMediaProcessor mediaProcessor = null)
        {
            Id = id;
            cancelFlag = new();
            this.http = http;
            this.mediaProcessor = mediaProcessor;
        }

        public void SetDownloadInfo(DualSourceHTTPDownloadInfo info)
        {
            this.state.Url1 = new Uri(info.Uri1);
            this.state.Url2 = new Uri(info.Uri2);
            this.state.Cookies1 = info.Cookies1;
            this.state.Cookies2 = info.Cookies2;
            this.state.Headers1 = info.Headers1;
            this.state.Headers2 = info.Headers2;
            this.SaveState();
        }

        public override void Start()
        {
            Start(true);
        }

        public void Start(bool start)
        {
            state.Init1 = state.Init2 = false;
            downloadSizeAtResume = 0;

            ticksAtDownloadStartOrResume = Helpers.TickCount();

            Directory.CreateDirectory(state.TempDir);
            var chunk1 = new Piece
            {
                Offset = 0,
                Length = -1,
                Downloaded = 0,
                State = SegmentState.NotStarted,
                Id = Guid.NewGuid().ToString(),
                StreamType = StreamType.Primary
            };
            pieces[chunk1.Id] = chunk1;
            grabberDict[chunk1.Id] = new PieceGrabber(chunk1.Id, this);

            var chunk2 = new Piece
            {
                Offset = 0,
                Length = -1,
                Downloaded = 0,
                State = SegmentState.NotStarted,
                Id = Guid.NewGuid().ToString(),
                StreamType = StreamType.Secondary
            };
            pieces[chunk2.Id] = chunk2;

            SaveState();
            if (start)
            {
                Log.Debug("DualSourceHTTPDownloader start");

                OnStarted();
                this.http ??= http = HttpClientFactory.NewHttpClient(state.Proxy);
                http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                grabberDict[chunk1.Id].Download();
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
                     Log.Debug("DualSourceHTTPDownloader Resume");

                     RestoreState();
                     Directory.CreateDirectory(state.TempDir);

                     if (pieces.Count != 0)
                     {
                         OnStarted();
                         Log.Debug("Chunks found: " + pieces.Count);
                         if (this.AllFinished())
                         {
                             this.AssemblePieces();
                             Log.Debug("Download finished");
                             base.OnFinished();
                             return;
                         }
                         else
                         {
                             this.http ??= HttpClientFactory.NewHttpClient(state.Proxy);
                             http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);
                             CreatePiece();
                         }
                     }
                     else
                     {
                         Log.Debug("Starting new download");
                         Start();
                     }
                 }
                 catch (Exception e)
                 {
                     Log.Debug(e, e.Message);
                     base.OnFailed(e is DownloadException ex ? ex.ErrorCode : ErrorCode.Generic);
                 }
             }).Start();
        }

        public override void PieceConnected(string pieceId, ProbeResult? result)
        {
            lock (this)
            {
                if (this.cancelFlag.IsCancellationRequested) return;
                var piece = this.pieces[pieceId];
                if (result != null) //probe result is not null only for first request of each stream type, for subsequent requests its always null
                {
                    state.LastModified = result.LastModified;
                    piece.Length = result.ResourceSize ?? -1;
                    piece.Offset = 0;
                    piece.Downloaded = 0;
                    piece.State = SegmentState.NotStarted;
                    if (piece.Length > 0)
                    {
                        totalSize += piece.Length;
                    }

                    Log.Debug("fileNameFetchMode: " + fileNameFetchMode);

                    switch (fileNameFetchMode)
                    {
                        case FileNameFetchMode.FileNameAndExtension:
                            if (result.AttachmentName != null)
                            {
                                this.TargetFileName = FileHelper.SanitizeFileName(result.AttachmentName);
                            }
                            else
                            {
                                this.TargetFileName = FileHelper.SanitizeFileName(FileHelper.GetFileName(
                                        result.FinalUri, result.ContentType));
                            }
                            break;
                        case FileNameFetchMode.ExtensionOnly:
                            if (state.Init1 || state.Init2)
                            {
                                FileHelper.AddFileExtension(this.TargetFileName, result.ContentType, out string name);
                                var ext1 = Path.GetExtension(this.TargetFileName);
                                var ext2 = Path.GetExtension(name);
                                if (ext1 == ".mkv" || ext2 == ".mkv")
                                {
                                    this.TargetFileName = Path.GetFileNameWithoutExtension(name) + ".mkv";
                                }
                            }
                            else
                            {
                                var name = string.Empty;
                                if (FileHelper.AddFileExtension(this.TargetFileName, result.ContentType, out name))
                                {
                                    this.TargetFileName = name;
                                }
                            }
                            break;
                    }

                    if (piece.StreamType == StreamType.Primary)
                    {
                        Log.Debug("Primary initiated - length: " + piece.Length);
                        state.Init1 = true;
                    }
                    else
                    {
                        Log.Debug("Secondary initiated - length: " + piece.Length);
                        state.Init2 = true;
                    }

                    if (piece.StreamType == StreamType.Primary)
                    {
                        state.Url1 = result.FinalUri;
                    }
                    else
                    {
                        state.Url2 = result.FinalUri;
                    }

                    if (state.Init1 && state.Init2)
                    {
                        state.FileSize = totalSize;
                    }

                    SaveState();
                    SaveChunkState();

                    if (state.Init1 && state.Init2)
                    {
                        base.OnProbed();
                    }

                    if (state.Init1 && state.Init2 &&
                        totalSize > 0 &&
                        Helpers.GetFreeSpace(this.state.TempDir, out long freespace) &&
                        freespace < totalSize)
                    {
                        throw new AssembleFailedException(ErrorCode.DiskError);
                    }
                }

                CreatePiece();
            }
        }

        public override (
            Dictionary<string, List<string>> Headers,
            Dictionary<string, string> Cookies,
            Uri Url,
            AuthenticationInfo? Authentication,
            ProxyInfo? Proxy)?
            GetHeaderUrlAndCookies(string pieceId)
        {
            if (this.grabberDict.ContainsKey(pieceId))
            {
                var piece = pieces[pieceId];
                return piece.StreamType == StreamType.Primary ?
                    (Headers: this.state.Headers1, Cookies: this.state.Cookies1, Url: this.state.Url1,
                    this.state.Authentication, this.state.Proxy) :
                    (Headers: this.state.Headers2, Cookies: this.state.Cookies2, Url: this.state.Url2,
                    this.state.Authentication, this.state.Proxy);
            }
            return null;
        }

        protected override BaseHTTPDownloaderState GetState()
        {
            return this.state;
        }

        public override bool IsFirstRequest(StreamType streamType)
        {
            return !(streamType == StreamType.Primary ? state.Init1 : state.Init2);
        }

        protected override void SaveChunkState()
        {
            lock (this)
            {
                if (pieces.Count == 0) return;
                TransactedIO.WriteStream("chunks.db", state!.TempDir!, base.ChunkStateToBytes);
            }
        }

        protected override void SaveState()
        {
            DownloadStateStore.Save(state);// TransactedIO.WriteBytes(DownloadStateStore.Save(state), Id + ".state", Config.DataDir);
        }

        public override void RestoreState()
        {
            state = DownloadStateStore.LoadDualSourceHTTPDownloaderState(Id!);
            //var bytes = TransactedIO.ReadBytes(Id + ".state", Config.DataDir);
            //if (bytes == null)
            //{
            //    throw new FileNotFoundException(Path.Combine(Config.DataDir, Id + ".state"));
            //}
            //state = DownloadStateStore.DualSourceHTTPDownloaderStateFromBytes(bytes);
            try
            {
                if (!TransactedIO.ReadStream("chunks.db", state!.TempDir!, s =>
                 {
                     pieces = ChunkStateFromBytes(s);
                 }))
                {
                    throw new FileNotFoundException(Path.Combine(state.TempDir, "chunks.db"));
                }
                //var chunkBytes = TransactedIO.ReadBytes("chunks.db", state.TempDir);
                //if (chunkBytes == null)
                //{
                //    throw new FileNotFoundException(Path.Combine(state.TempDir, "chunks.json"));
                //}
                //pieces = ChunkStateFromBytes(chunkBytes);
            }
            catch
            {
                // ignored
                Log.Debug("Chunk restore failed");
            }
            TicksAndSizeAtResume();
        }

        protected override void AssemblePieces()
        {
            Log.Debug("Assembling..." + this.Id);

            lock (this)
            {
#if NET35
                var buf = new byte[5 * 1024 * 1024];
#else
                var buf = System.Buffers.ArrayPool<byte>.Shared.Rent(5 * 1024 * 1024);
#endif
                try
                {
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

                    //check if required disk space is available
                    if (Helpers.GetFreeSpace(this.TargetDir, out long freespace))
                    {
                        if (freespace < FileSize)
                        {
                            throw new AssembleFailedException(ErrorCode.DiskError);
                        }
                    }

                    Log.Debug("Assembling...");
                    var pieces = this.pieces.Select(p => p.Value).ToList();
                    pieces.Sort((a, b) =>
                    {
                        var diff = a.Offset - b.Offset;
                        if (diff == 0) return 0;
                        return diff > 0 ? 1 : -1;
                    });
                    if (this.cancelFlag.IsCancellationRequested) return;
                    var file1 = Path.Combine(this.state.TempDir, "1_" + this.Id);
                    var file2 = Path.Combine(this.state.TempDir, "2_" + this.Id);
                    using var outfs1 = new FileStream(file1, FileMode.Create, FileAccess.Write);
                    using var outfs2 = new FileStream(file2, FileMode.Create, FileAccess.Write);
                    var totalBytes = 0L;

                    var plist1 = pieces.Where(pc => pc.StreamType == StreamType.Primary).ToList();
                    var plist2 = pieces.Where(pc => pc.StreamType == StreamType.Secondary).ToList();

                    AssemblePieces(plist1, outfs1, ref buf, ref totalBytes);
                    AssemblePieces(plist2, outfs2, ref buf, ref totalBytes);

                    outfs1.Close();
                    outfs2.Close();

                    if (this.cancelFlag.IsCancellationRequested) return;

                    if (mediaProcessor != null)
                    {
                        mediaProcessor.ProgressChanged += (s, e) =>
                        {
                            var basePrg = 60;
                            var prg = basePrg + e.Progress / 3;
                            if (prg > 100) prg = 100;
                            this.OnAssembleProgressChanged(prg);
                        };
                        var res = mediaProcessor.MergeAudioVideStream(file1, file2, TargetFile,
                            this.cancelFlag, out totalBytes);
                        if (this.cancelFlag.IsCancellationRequested) return;
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

                    if (this.cancelFlag.IsCancellationRequested) return;

                    if (this.totalSize < 1)
                    {
                        this.totalSize = totalBytes;
                    }
                    if (this.cancelFlag.IsCancellationRequested) return;
                    Log.Debug("Deleting file parts");
                    DeleteFileParts();
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "error");
                    var aex = new AssembleFailedException(ex is DownloadException de ? de.ErrorCode : ErrorCode.Generic);
                    throw aex;
                }
                finally
                {
#if !NET35
                    System.Buffers.ArrayPool<byte>.Shared.Return(buf);
#endif
                }
            }
        }

        private void AssemblePieces(IList<Piece> pieces, FileStream outfs, ref byte[] buf, ref long totalBytes)
        {
            var bytes = 0L;
            var streamSize = 0L;
            if (this.FileSize > 0)
            {
                foreach (var pc in pieces)
                {
                    streamSize += pc.Length;
                }
            }
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
                            throw new AssembleFailedException(ErrorCode.DiskError, ioe);
                        }
                        totalBytes += x;
                        bytes += x;
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
                            throw new AssembleFailedException(ErrorCode.DiskError, ioe);
                        }
                        len -= x;
                        totalBytes += x;
                        bytes += x;
                        if (streamSize > 0)
                        {
                            var progress = (int)Math.Ceiling(totalBytes * 100 / (double)streamSize * 3);
                            if (progress > 100) progress = 100;
                            this.OnAssembleProgressChanged(progress);
                        }
                    }
                }
            }
        }

        public override void ThrottleIfNeeded()
        {
            base.ThrottleIfNeeded(this.state!);
        }

        public override bool IsTextRedirectionAllowed() { return true; }

        public override bool IsFileChangedOnServer(StreamType streamType, long streamSize, DateTime? lastModified)
        {
            return false;
        }

        public override void UpdateSpeedLimit(bool enable, int limit)
        {
            base.UpdateSpeedLimit(this.state, enable, limit);
        }
    }

    public class DualSourceHTTPDownloaderState : BaseHTTPDownloaderState
    {
        public Uri Url1;
        public Uri Url2;
        public Dictionary<string, List<string>> Headers1;
        public Dictionary<string, List<string>> Headers2;
        public Dictionary<string, string> Cookies1;
        public Dictionary<string, string> Cookies2;
        public bool Init1, Init2;
    }

    //internal class CustomRedirectHandler : DelegatingHandler
    //{
    //    public CustomRedirectHandler(HttpMessageHandler handler) : base(handler) { }
    //    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    //    {
    //        var res = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
    //        if ((res.StatusCode == System.Net.HttpStatusCode.OK ||
    //            res.StatusCode == System.Net.HttpStatusCode.PartialContent) &&
    //            res.Content?.Headers?.ContentType?.MediaType == "text/plain")
    //        {
    //            Log.Debug("Rediction...");
    //            var newUrl = await res.Content.ReadAsStringAsync().ConfigureAwait(false);
    //            cancellationToken.ThrowIfCancellationRequested();
    //            //#if NET5_0_OR_GREATER
    //            //                var newUrl = await res.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
    //            //#else
    //            //                var newUrl = await res.Content.ReadAsStringAsync().ConfigureAwait(false);
    //            //                cancellationToken.ThrowIfCancellationRequested();
    //            //#endif

    //            Log.Error("Special redirect to: " + newUrl);
    //            request.RequestUri = new Uri(newUrl);
    //            res = await base.SendAsync(request, cancellationToken).ConfigureAwait(false);
    //        }
    //        return res;
    //    }

    //    //private async Task ReadAsStringAsync(HttpContent hc,CancellationToken token)
    //    //{
    //    //    var str=hc.CopyToAsync()
    //    //}
    //}


}
