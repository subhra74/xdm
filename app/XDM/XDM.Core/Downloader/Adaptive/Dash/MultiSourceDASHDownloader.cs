using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using TraceLog;
using XDM.Core.Clients.Http;
using XDM.Core;
using XDM.Core.MediaProcessor;
using XDM.Core.Util;
using XDM.Core.IO;

namespace XDM.Core.Downloader.Adaptive.Dash
{
    public class MultiSourceDASHDownloader : MultiSourceDownloaderBase
    {
        public override string Type => "Mpd-Dash";
        public override Uri PrimaryUrl
        {
            get
            {
                var state = _state as MultiSourceDASHDownloadState;
                return state == null ? null : new Uri(state.Url);
            }
        }
        public MultiSourceDASHDownloader(MultiSourceDASHDownloadInfo info, IHttpClient http = null,
            BaseMediaProcessor mediaProcessor = null,
            AuthenticationInfo? authentication = null, ProxyInfo? proxy = null,
            int speedLimit = 0) : base(info, http, mediaProcessor)
        {
            var state = new MultiSourceDASHDownloadState
            {
                Id = base.Id,
                Demuxed = info.VideoSegments != null && info.AudioSegments != null,
                Cookies = info.Cookies,
                Headers = info.Headers,
                Url = info.Url,
                Authentication = authentication,
                Proxy = proxy,
                TempDirectory = Path.Combine(Config.Instance.TempDir, Id),
                SpeedLimit = speedLimit
            };

            if (state.Authentication == null)
            {
                state.Authentication = Helpers.GetAuthenticationInfoFromConfig(new Uri(info.Url));
            }

            this._state = state;
            this.TargetFileName = FileHelper.SanitizeFileName(info.File);

            state.FileSize = -1;
            var i = 0;

            if (state.Demuxed)
            {
                state.AudioChunkCount = info.AudioSegments.Count;
                state.VideoChunkCount = info.VideoSegments.Count;

                state.AudioSegments = info.AudioSegments;
                state.VideoSegments = info.VideoSegments;

                state.Duration = info.Duration;
                state.AudioContainerFormat = info.AudioFormat ?? FileExtensionHelper.GetExtensionFromMimeType(info.AudioMimeType)
                    ?? GuessContainerFormatFromPlaylist(info.AudioSegments);
                state.VideoContainerFormat = info.VideoFormat ?? FileExtensionHelper.GetExtensionFromMimeType(info.VideoMimeType)
                    ?? GuessContainerFormatFromPlaylist(info.VideoSegments);

                CreateChunks2(state, _chunks, _chunkStreamMap);

                //for (; i < Math.Min(this._state.AudioChunkCount, this._state.VideoChunkCount); i++)
                //{
                //    var chunk1 = CreateChunk(info.VideoSegments[i], 0);
                //    _chunks.Add(chunk1);
                //    _chunkStreamMap.StreamMap[chunk1.Id] = Path.Combine(_state.TempDirectory, "1_" + chunk1.Id + FileHelper.GetFileName(chunk1.Uri));

                //    var chunk2 = CreateChunk(info.AudioSegments[i], 1);
                //    _chunks.Add(chunk2);
                //    _chunkStreamMap.StreamMap[chunk2.Id] = Path.Combine(_state.TempDirectory, "2_" + chunk2.Id + FileHelper.GetFileName(chunk2.Uri));
                //}
                //for (; i < this._state.VideoChunkCount; i++)
                //{
                //    var chunk = CreateChunk(info.VideoSegments[i], 0);
                //    _chunks.Add(chunk);
                //    _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(_state.TempDirectory, "1_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                //}
                //for (; i < this._state.AudioChunkCount; i++)
                //{
                //    var chunk = CreateChunk(info.AudioSegments[i], 1);
                //    _chunks.Add(chunk);
                //    _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(_state.TempDirectory, "2_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                //}

                var ext = FileExtensionHelper.GetExtensionFromMimeType(info.VideoMimeType) ??
                    FileExtensionHelper.GuessContainerFormatFromSegmentExtension(state.VideoContainerFormat);

                //if (!(string.IsNullOrWhiteSpace(state.VideoContainerFormat) || state.VideoContainerFormat == "."))
                //{
                //    ext = Helpers.GetExtensionFromMimeType(info.VideoMimeType)
                //    ?? Helpers.GuessContainerFormatFromSegmentExtension(
                //        state.VideoContainerFormat.ToLowerInvariant(), true);
                //}
                TargetFileName = Path.GetFileNameWithoutExtension(TargetFileName ?? "video")
                        + ext;
            }
            else
            {
                var segments = info.VideoSegments ?? info.AudioSegments;
                state.VideoChunkCount = segments.Count;
                state.VideoSegments = segments;
                state.Duration = info.Duration;

                CreateChunks1(state, _chunks, _chunkStreamMap);

                //for (; i < this._state.VideoChunkCount; i++)
                //{
                //    var chunk = CreateChunk(segments[i], 0);
                //    _chunks.Add(chunk);
                //    _chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(_state.TempDirectory, "1_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                //}

                state.VideoContainerFormat = GuessContainerFormatFromPlaylist(segments);
                var ext = FileExtensionHelper.GuessContainerFormatFromSegmentExtension(
                            this._state.VideoContainerFormat.ToLowerInvariant());
                TargetFileName = Path.GetFileNameWithoutExtension(TargetFileName ?? "video")
                            + ext;
            }
        }

        public MultiSourceDASHDownloader(string id, IHttpClient http = null,
            BaseMediaProcessor mediaProcessor = null) : base(id, http, mediaProcessor)
        {

        }

        private static MultiSourceChunk CreateChunk(Uri mediaSegment, int streamIndex)
        {
            Log.Debug(streamIndex + "-Url: " + mediaSegment);
            return new MultiSourceChunk
            {
                Uri = mediaSegment,
                ChunkState = ChunkState.Ready,
                Id = Guid.NewGuid().ToString(),
                Offset = 0,
                Size = -1,
                StreamIndex = streamIndex
            };
        }

        protected override void Init(string tempDir)
        {
            //Nothing to do here
            //return Task.FromResult(string.Empty);// new  Task.CompletedTask;
        }

        protected override void OnContentTypeReceived(Chunk chunk, string contentType)
        {
        }

        protected override void RestoreState()
        {
            var state = DownloadStateIO.LoadMultiSourceDASHDownloadState(Id!);
            this._state = state;

            //var bytes = TransactedIO.ReadBytes(Id + ".state", Config.DataDir);
            //if (bytes == null)
            //{
            //    throw new FileNotFoundException(Path.Combine(Config.DataDir, Id + ".state"));
            //}

            //var state = DownloadStateStore.MultiSourceDASHDownloadStateFromBytes(bytes);
            //this._state = state;

            //var text = TransactedIO.Read(Id + ".state", Config.DataDir);
            //if (text == null)
            //{
            //    throw new FileNotFoundException(Path.Combine(Config.DataDir, Id + ".state"));
            //}
            ////since all information is available in constructor we assume chunk restore can not fail
            //var state = JsonConvert.DeserializeObject<MultiSourceDASHDownloadState>(
            //                     text);
            //this._state = state;

            try
            {
                Log.Debug("Restoring chunks from: " + Path.Combine(_state.TempDirectory, "chunks.db"));

                if (!TransactedIO.ReadStream("chunks.db", state.TempDirectory, s =>
                {
                    _chunks = ChunkStateFromBytes(s);// pieces = ChunkStateFromBytes(s);
                }))
                {
                    throw new FileNotFoundException(Path.Combine(state.TempDirectory, "chunks.db"));
                }

                //var bytes2 = TransactedIO.ReadBytes("chunks.db", _state.TempDirectory);
                //if (bytes2 == null)
                //{
                //    throw new FileNotFoundException(Path.Combine(_state.TempDirectory, "chunks.json"));
                //}

                //_chunks = ChunkStateFromBytes(bytes2);

                var dashDir = _state.TempDirectory;
                var streamMap = _chunks.Select(c => new
                {
                    c.Id,
                    TempFilePath = Path.Combine(dashDir, (c.StreamIndex == 0 ? "1_" : "2_") + c.Id + FileHelper.GetFileName(c.Uri))
                }).ToDictionary(e => e.Id, e =>
                        e.TempFilePath);
                _chunkStreamMap = new SimpleStreamMap { StreamMap = streamMap };

            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error loading chunks");

                if (state.Demuxed)
                {
                    CreateChunks2(state, _chunks, _chunkStreamMap);
                }
                else
                {
                    CreateChunks1(state, _chunks, _chunkStreamMap);
                }
            }


            var count = 0;
            downloadedBytes = 0;
            _chunks.ForEach(c =>
            {
                if (c.ChunkState == ChunkState.Finished) count++;
                if (c.Downloaded > 0) downloadedBytes += c.Downloaded;
            });

            this.downloadSizeAtResume = downloadedBytes;
            this.lastProgress = (count * 100) / _chunks.Count;
            ticksAtDownloadStartOrResume = Helpers.TickCount();
            Log.Debug("Already downloaded: " + count);
        }

        protected override void SaveState()
        {
            DownloadStateIO.Save((MultiSourceDASHDownloadState)_state);
            //TransactedIO.WriteBytes(DownloadStateStore.Save((MultiSourceDASHDownloadState)_state), Id + ".state", Config.DataDir);
            //TransactedIO.Write(JsonConvert.SerializeObject(_state as MultiSourceDASHDownloadState),
            //    Id + ".state", Config.DataDir);

            //File.WriteAllText(Path.Combine(Config.DataDir, Id + ".state"),
            //    JsonConvert.SerializeObject(_state as MultiSourceDASHDownloadState));
            SaveChunkState();
        }

        //private ISet<string> GetAllHosts(params List<Uri>[] args)
        //{
        //    var set = new HashSet<string>();
        //    foreach (var arg in args)
        //    {
        //        if (arg == null) continue;
        //        foreach (var url in arg)
        //        {
        //            set.Add(url.Scheme + "://" + url.Authority + "/");
        //        }
        //    }
        //    return set;
        //}

        private static string GuessContainerFormatFromPlaylist(List<Uri> segments)
        {
            var file = FileHelper.GetFileName(segments.Last());
            return Path.GetExtension(file);
        }

        private static void CreateChunks2(MultiSourceDASHDownloadState state, List<MultiSourceChunk> chunks, SimpleStreamMap chunkStreamMap)
        {
            var i = 0;
            if (state.Demuxed && state.VideoSegments != null && state.AudioSegments != null)
            {
                for (; i < Math.Min(state.AudioChunkCount, state.VideoChunkCount); i++)
                {
                    var chunk1 = CreateChunk(state.VideoSegments[i], 0);
                    chunks.Add(chunk1);
                    chunkStreamMap.StreamMap[chunk1.Id] = Path.Combine(state.TempDirectory, "1_" + chunk1.Id + FileHelper.GetFileName(chunk1.Uri));

                    var chunk2 = CreateChunk(state.AudioSegments[i], 1);
                    chunks.Add(chunk2);
                    chunkStreamMap.StreamMap[chunk2.Id] = Path.Combine(state.TempDirectory, "2_" + chunk2.Id + FileHelper.GetFileName(chunk2.Uri));
                }
                for (; i < state.VideoChunkCount; i++)
                {
                    var chunk = CreateChunk(state.VideoSegments[i], 0);
                    chunks.Add(chunk);
                    chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(state.TempDirectory, "1_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                }
                for (; i < state.AudioChunkCount; i++)
                {
                    var chunk = CreateChunk(state.AudioSegments[i], 1);
                    chunks.Add(chunk);
                    chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(state.TempDirectory, "2_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                }
            }
        }

        private static void CreateChunks1(MultiSourceDASHDownloadState state, List<MultiSourceChunk> chunks, SimpleStreamMap chunkStreamMap)
        {
            var i = 0;
            var segments = state.VideoSegments ?? state.AudioSegments;
            if (segments != null)
            {
                for (; i < state.VideoChunkCount; i++)
                {
                    var chunk = CreateChunk(segments[i], 0);
                    chunks.Add(chunk);
                    chunkStreamMap.StreamMap[chunk.Id] = Path.Combine(state.TempDirectory, "1_" + chunk.Id + FileHelper.GetFileName(chunk.Uri));
                }
            }
        }
    }

    public class MultiSourceDASHDownloadState : MultiSourceDownloadState
    {
        public string? Url { get; set; }
        public List<Uri>? AudioSegments { get; set; }
        public List<Uri>? VideoSegments { get; set; }
    }
}
