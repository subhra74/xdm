using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;
using XDM.Core.Lib.Clients.Http;
using XDM.Core.Lib.Common.MediaProcessor;
using System.Text;

#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

namespace XDM.Core.Lib.Downloader.Progressive
{
    public abstract class HTTPDownloaderBase : IPieceCallback, IBaseDownloader
    {
        protected Dictionary<string, Piece> pieces = new();
        protected Dictionary<string, PieceGrabber> grabberDict = new();
        protected bool resumable;
        protected long totalSize;
        protected int MAX_COUNT = Config.Instance.MaxSegments;
        protected CancelFlag cancelFlag = new();
        protected FileNameFetchMode fileNameFetchMode = FileNameFetchMode.FileNameAndExtension;
        protected IHttpClient http;
        protected long lastStateSavedAt = Helpers.TickCount();
        protected long lastProgressUpdatedAt = Helpers.TickCount();
        protected readonly ProgressResultEventArgs progressResult = new();
        //protected readonly CookieContainer cookieContainer = new();
        protected long downloadedBytes = 0L;
        protected long downloadSizeAtResume = 0L;
        protected long lastDownloadedBytes = 0L;
        protected long ticksAtDownloadStartOrResume = 0L;
        protected SpeedLimiter speedLimiter = new();
        protected BaseMediaProcessor? mediaProcessor;

        public FileNameFetchMode FileNameFetchMode
        {
            get { return fileNameFetchMode; }
            set { fileNameFetchMode = value; }
        }

        public string? TargetDir { get; set; }
        public bool IsCancelled => cancelFlag.IsCancellationRequested;
        public string? Id { get; protected set; }
        public long FileSize => this.GetState().FileSize > 0 ? this.GetState().FileSize : totalSize;
        public string? TargetFile
        {
            get
            {
                if (TargetDir == null || TargetFileName == null)
                {
                    return null;
                }
                return Path.Combine(TargetDir,
                        TargetFileName);
            }
        }
        public string? TargetFileName { get; set; }
        public bool KeepProvidedFileName { get; set; }
        public virtual string Type => "Http";
        public virtual Uri? PrimaryUrl { get; }

        public abstract int SpeedLimit { get; }

        public abstract bool EnableSpeedLimit { get; }

        public virtual event EventHandler? Started;
        public virtual event EventHandler? Finished;
        public virtual event EventHandler? Cancelled;
        public virtual event EventHandler? Probed;
        public virtual event EventHandler<ProgressResultEventArgs>? ProgressChanged;
        public virtual event EventHandler<ProgressResultEventArgs>? AssembingProgressChanged;
        public virtual event EventHandler<DownloadFailedEventArgs>? Failed;

        public abstract void Start();

        public abstract void SaveForLater();

        public abstract void Resume();
        public abstract bool IsTextRedirectionAllowed();

        public abstract (
            Dictionary<string, List<string>> Headers,
            Dictionary<string, string> Cookies,
            Uri Url, AuthenticationInfo? Authentication,
            ProxyInfo? Proxy)?
            GetHeaderUrlAndCookies(string pieceId);

        public abstract void PieceConnected(string pieceId, ProbeResult? result);

        protected abstract void SaveChunkState();

        protected abstract void SaveState();

        protected abstract BaseHTTPDownloaderState GetState();

        protected abstract void AssemblePieces();

        public abstract bool IsFirstRequest(StreamType streamType);

        public abstract bool IsFileChangedOnServer(StreamType streamType, long streamSize, DateTime? lastModified);

        public abstract void RestoreState();

        public abstract void UpdateSpeedLimit(bool enable, int limit);

        public abstract void ThrottleIfNeeded();

        protected void ThrottleIfNeeded(int speedLimit)
        {
            speedLimiter.ThrottleIfNeeded(this, speedLimit);
        }

        protected void ThrottleIfNeeded(BaseHTTPDownloaderState? state)
        {
            int speedLimit = 0;
            lock (state!)
            {
                speedLimit = state.SpeedLimit;
            }
            lock (this)
            {
                //if (speedLimit < 1 && Config.Instance.EnableSpeedLimit && Config.Instance.DefaltDownloadSpeed > 0)
                //{
                //    speedLimit = Config.Instance.DefaltDownloadSpeed;
                //}
                if (speedLimit > 0)
                {
                    ThrottleIfNeeded(speedLimit);
                }
            }
        }

        public virtual void Stop()
        {
            try
            {
                this.cancelFlag.Cancel();
                this.speedLimiter.WakeIfSleeping();
                foreach (var pc in grabberDict.Keys)
                {
                    grabberDict[pc].Stop();
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error while Stop");
            }
            OnCancelled();
        }

        public virtual void SetTargetDirectory(string? folder)
        {
            this.TargetDir = folder;
        }

        public virtual void SetUserSelectedFile(string file)
        {
            var folder = Path.GetDirectoryName(file);
            var name = Path.GetFileName(file);
            this.TargetFileName = Helpers.SanitizeFileName(name);
            this.TargetDir = folder;
            this.KeepProvidedFileName = true;
        }

        /// <summary>
        /// 
        /// </summary>
        /// <param name="name"></param>
        /// <param name="fetchExtension"></param>
        public virtual void SetFileName(string name, FileNameFetchMode fileNameFetchMode)
        {
            this.TargetFileName = Helpers.SanitizeFileName(name);
            this.fileNameFetchMode = fileNameFetchMode;
        }

        public virtual bool ContinueAdjacentPiece(string pieceId, long maxByteRange)
        {
            lock (this)
            {
                var chunk = pieces[pieceId];
                var position = chunk.Offset + chunk.Length;
                foreach (var key in this.pieces.Keys)
                {
                    var val = this.pieces[key];
                    if (val.Downloaded == 0 && val.Offset == position &&
                        val.StreamType == chunk.StreamType &&
                        !this.cancelFlag.IsCancellationRequested)
                    {
                        if ((val.Offset + val.Length) <= maxByteRange)
                        {
                            var grabber = this.grabberDict.GetValueOrDefault(val.Id, null);
                            if (grabber != null)
                            {
                                grabber.Stop();
                                this.grabberDict.Remove(val.Id);
                            }
                            this.pieces.Remove(key);
                            var len = val.Length;
                            chunk.Length += len;
                            this.CreatePiece();
                            this.SaveChunkState();
                            return true;
                        }
                        Log.Debug("ContinueAdjacentPiece fail bcs of " + maxByteRange + "<" + ((val.Offset + val.Length)));
                    }
                }
                return false;
            }
        }

        public virtual Piece GetPiece(string pieceId)
        {
            lock (this)
            {
                return this.pieces[pieceId];
            }
        }

        public virtual string GetPieceFile(string pieceId)
        {
            return Path.Combine(this.GetState().TempDir, pieceId);
        }

        public virtual IHttpClient? GetSharedHttpClient(string pieceId)
        {
            if (this.grabberDict.ContainsKey(pieceId))
            {
                return this.http;
            }
            return null;
        }

        public void PieceDownloadFailed(string pieceId, ErrorCode error)
        {
            lock (this)
            {
                if (this.cancelFlag.IsCancellationRequested) return;
                grabberDict.Remove(pieceId);
                this.SaveChunkState();
                if (grabberDict.Count == 0)
                {
                    OnFailed(error);
                }
            }
        }

        public virtual void PieceDownloadFinished(string pieceId)
        {
            lock (this)
            {
                if (this.cancelFlag.IsCancellationRequested) return;
                var piece = this.pieces[pieceId];
                piece.State = SegmentState.Finished;
                grabberDict.Remove(pieceId);
                SaveChunkState();
                if (this.AllFinished())
                {
                    SaveChunkState();
                    this.AssemblePieces();
                    OnFinished();
                    return;
                }
            }
            this.CreatePiece();
        }

        public virtual void UpdateDownloadedBytesCount(string pieceId, long bytes)
        {
            lock (this)
            {
                downloadedBytes += bytes;

                var pc = pieces[pieceId];
                pc.Downloaded += bytes;

                var ticks = Helpers.TickCount();

                if (ticks - lastStateSavedAt > 2000)
                {
                    SaveChunkState();
                    lastStateSavedAt = ticks;
                }

                var ticksElapsed = ticks - ticksAtDownloadStartOrResume;
                if (ticks - lastProgressUpdatedAt > 500 && ticksElapsed > 0)
                {
                    var instantSpeed = ((downloadedBytes - lastDownloadedBytes) * 1000) / (ticks - lastProgressUpdatedAt);
                    var avgSpeed = ((downloadedBytes - downloadSizeAtResume) * 1000.0) / ticksElapsed;
                    lastProgressUpdatedAt = ticks;
                    lastDownloadedBytes = downloadedBytes;
                    progressResult.DownloadSpeed = instantSpeed;//((downloadedBytes - downloadSizeAtResume) * 1000.0) / ticksElapsed;
                    progressResult.Downloaded = downloadedBytes;
                    progressResult.Progress = FileSize > 0 ? (int)(downloadedBytes * 100 / FileSize) : 0;
                    progressResult.Eta = FileSize > 0 ? (long)Math.Ceiling((FileSize - downloadedBytes) / avgSpeed /*progressResult.DownloadSpeed*/) : 0;
                    ProgressChanged?.Invoke(this, progressResult);
                }
            }
        }

        protected virtual void CreatePiece()
        {
            lock (this)
            {
                if (this.cancelFlag.IsCancellationRequested) return;
                if (this.grabberDict.Count == MAX_COUNT) return;
                var rem = MAX_COUNT - this.grabberDict.Count;
                rem -= RetryFailedPieces(rem);
                if (rem > 0 && !this.cancelFlag.IsCancellationRequested)
                {
                    var maxChunkId = FindMaxChunk();
                    var newchunkId = SplitPiece(maxChunkId);
                    if (newchunkId != null && !this.cancelFlag.IsCancellationRequested)
                    {
                        this.grabberDict[newchunkId] = new PieceGrabber(newchunkId, this);
                        grabberDict[newchunkId].Download();
                    }
                }
                SaveChunkState();
            }
        }

        protected virtual bool AllFinished()
        {
            lock (this)
            {
                foreach (var pi in this.pieces.Keys)
                {
                    if (this.pieces[pi].State != SegmentState.Finished) return false;
                }
                return true;
            }
        }

        protected int RetryFailedPieces(int max)
        {
            lock (this)
            {
                var count = 0;
                for (var chunkToRetry = Math.Min(GetInactiveChunkCount(), max); chunkToRetry > 0; chunkToRetry--)
                {
                    var chunkId = GetInactivePiece();
                    if (chunkId == null || this.cancelFlag.IsCancellationRequested) break;
                    grabberDict[chunkId] = new PieceGrabber(chunkId, this);
                    grabberDict[chunkId].Download();
                    count++;
                }
                return count;
            }
        }

        protected int GetInactiveChunkCount()
        {
            lock (this)
            {
                return pieces.Keys.Where(chunkId => !(grabberDict.ContainsKey(chunkId)
                || pieces[chunkId].State == SegmentState.Finished)).Count();
            }
        }

        protected string? GetInactivePiece()
        {
            lock (this)
            {
                foreach (var chunkId in pieces.Keys)
                {
                    if (!(grabberDict.ContainsKey(chunkId) || pieces[chunkId].State == SegmentState.Finished
                        || pieces[chunkId].Downloaded == pieces[chunkId].Length)) return chunkId;
                }
                return null;
            }
        }

        protected string? FindMaxChunk()
        {
            lock (this)
            {
                var max = -1L;
                string? pid = null;
                foreach (var pieceId in this.pieces.Keys)
                {
                    if (this.cancelFlag.IsCancellationRequested) break;
                    var rem = this.pieces[pieceId].Length - this.pieces[pieceId].Downloaded;
                    if (rem > max)
                    {
                        pid = pieceId;
                        max = rem;
                    }
                }
                return max > 256 * 1024 ? pid : null;
            }
        }

        protected string? SplitPiece(string chunkId)
        {
            lock (this)
            {
                if (chunkId != null && !this.cancelFlag.IsCancellationRequested)
                {
                    var chunk = pieces[chunkId];
                    var rem = chunk.Length - chunk.Downloaded;
                    if (rem < 256 * 1024)
                    {
                        return null;
                    }
                    var len = rem / 2;
                    var offset = chunk.Offset + chunk.Length - len;
                    chunk.Length -= len;
                    var newPieceId = Guid.NewGuid().ToString();
                    var newPiece = new Piece
                    {
                        Offset = offset,
                        Length = len,
                        Downloaded = 0,
                        State = SegmentState.NotStarted,
                        Id = newPieceId,
                        StreamType = chunk.StreamType
                    };
                    pieces[newPieceId] = newPiece;
                    SaveChunkState();
                    return newPieceId;
                }
                return null;
            }
        }

        protected virtual void OnStarted()
        {
            this.Started?.Invoke(this, EventArgs.Empty);
        }

        protected virtual void OnFinished()
        {
            this.Finished?.Invoke(this, EventArgs.Empty);
            Cleanup();
        }

        protected virtual void OnProgressChanged(int progress)
        {
            this.progressResult.Progress = progress;
            this.ProgressChanged?.Invoke(this, progressResult);
        }

        protected virtual void OnCancelled()
        {
            this.Cancelled?.Invoke(this, EventArgs.Empty);
            Cleanup();
        }

        protected virtual void OnProbed()
        {
            this.Probed?.Invoke(this, EventArgs.Empty);
        }

        protected virtual void OnFailed(ErrorCode error)
        {
            if (error == ErrorCode.InvalidResponse && downloadedBytes > 0)
            {
                this.Failed?.Invoke(this, new DownloadFailedEventArgs(ErrorCode.SessionExpired));
            }
            else
            {
                this.Failed?.Invoke(this, new DownloadFailedEventArgs(error));
            }
            Cleanup();
        }

        protected virtual void OnAssembleProgressChanged(int progress)
        {
            this.progressResult.Progress = progress;
            this.AssembingProgressChanged?.Invoke(this, progressResult);
        }

        protected virtual void TicksAndSizeAtResume()
        {
            if (pieces == null) return;
            downloadedBytes = 0;

            ticksAtDownloadStartOrResume = Helpers.TickCount();

            foreach (var pk in pieces.Keys)
            {
                downloadedBytes += pieces[pk].Downloaded;
            }
            downloadSizeAtResume = downloadedBytes;
        }

        private void Cleanup()
        {
            try
            {
                this.http?.Dispose();
            }
            catch (Exception e)
            {
                Log.Debug(e, "Exception while disposing http client");
            }
        }

        public long GetDownloaded() => this.downloadedBytes;

        protected virtual void DeleteFileParts()
        {
            try
            {
                Log.Debug("Deleting temp files in: " + this.GetState().TempDir);
                Directory.Delete(this.GetState().TempDir, true);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "DeleteFileParts");
            }
        }

        protected static void WriteChunkState(Dictionary<string, Piece> chunks, BinaryWriter w)
        {
            w.Write(chunks.Count);
            foreach (var key in chunks.Keys)
            {
                w.Write(key);
                var chunk = chunks[key];
                w.Write(chunk.Id);
                w.Write(chunk.Downloaded);
                w.Write(chunk.Length);
                w.Write(chunk.Offset);
                w.Write((int)chunk.State);
                w.Write((int)chunk.StreamType);
            }
        }

        protected static void ReadChunkState(BinaryReader r, out Dictionary<string, Piece> chunks)
        {
            var count = r.ReadInt32();
            chunks = new();
            for (var i = 0; i < count; i++)
            {
                var key = r.ReadString();
                chunks[key] = new Piece
                {
                    Id = r.ReadString(),
                    Downloaded = r.ReadInt64(),
                    Length = r.ReadInt64(),
                    Offset = r.ReadInt64(),
                    State = (SegmentState)r.ReadInt32(),
                    StreamType = (StreamType)r.ReadInt32()
                };
            }
        }

        protected Dictionary<string, Piece> ChunkStateFromBytes(Stream stream)
        {
#if NET35
            var ms = new MemoryStream();
            stream.CopyTo(ms);
            using var r = new BinaryReader(ms);
            ReadChunkState(r, out Dictionary<string, Piece> chunks);
            return chunks;
#else
            using var r = new BinaryReader(stream, Encoding.UTF8, true);
            ReadChunkState(r, out Dictionary<string, Piece> chunks);
            return chunks;
#endif
        }

        protected void ChunkStateToBytes(Stream stream)
        {
#if NET35
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms, Encoding.UTF8);
            WriteChunkState(pieces, w);
            ms.CopyTo(stream);
#else
            using var w = new BinaryWriter(stream, Encoding.UTF8, true);
            WriteChunkState(pieces, w);
#endif
        }

        protected void UpdateSpeedLimit(BaseHTTPDownloaderState? state, bool enable, int limit)
        {
            if (state == null) return;
            if (!enable)
            {
                limit = 0;
            }
            lock (state)
            {
                state.SpeedLimit = limit;
                SaveState();
            }
        }
    }

    public class BaseHTTPDownloaderState
    {
        public string? TempDir;
        public string? Id;
        public long FileSize = -1;
        public AuthenticationInfo? Authentication;
        public ProxyInfo? Proxy;
        public DateTime LastModified;
        public int SpeedLimit;
    }

    public enum DownloadState
    {
        Created, Downloading, Stopped, Failed, Finished, Assembling
    }
}
