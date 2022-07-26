using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Util;
using XDM.Messaging;
#if NET35
using XDM.Compatibility;
#endif

namespace XDM.Core.IO
{
    delegate void BinaryReaderStreamConsumer(BinaryReader r);
    delegate void BinaryWriterStreamConsumer(BinaryWriter w);

    internal static class TransactedBinaryDataReader
    {
        public static void Read(string file, string folder, BinaryReaderStreamConsumer callback)
        {
            if (!TransactedIO.ReadStream(file, folder, stream =>
            {
#if NET35
                using var ms = new MemoryStream();
                stream.CopyTo(ms);
                using var r = new BinaryReader(ms);
                callback(r);
#else
                using var r = new BinaryReader(stream, Encoding.UTF8, true);
                callback(r);
#endif
            }))
            {
                throw new IOException(Path.Combine(Config.DataDir, file));
            }
        }

        public static void Write(string file, string folder, BinaryWriterStreamConsumer callback)
        {
            TransactedIO.WriteStream(file, folder, stream =>
            {
#if NET35
                using var ms = new MemoryStream();
                using var w = new BinaryWriter(ms);
#else
                using var w = new BinaryWriter(stream, Encoding.UTF8, true);
#endif
                callback(w);
#if NET35
                ms.CopyTo(stream);
#endif
            });
        }
    }

    public static class DownloadStateStore
    {
        public static SingleSourceHTTPDownloaderState LoadSingleSourceHTTPDownloaderState(string id)
        {
            SingleSourceHTTPDownloaderState? state = null;
            TransactedBinaryDataReader.Read($"{id}.state", Config.DataDir, r =>
            {
                state = SingleSourceHTTPDownloaderStateFromBytes(r);
            });
            if (state == null)
            {
                throw new IOException("Unable to read state: " + id);
            }
            return state;
        }

        private static SingleSourceHTTPDownloaderState SingleSourceHTTPDownloaderStateFromBytes(BinaryReader r)
        {
            var state = new SingleSourceHTTPDownloaderState
            {
                Id = r.ReadString(),
                TempDir = XDM.Messaging.StreamHelper.ReadString(r),
                FileSize = r.ReadInt64(),
                LastModified = DateTime.FromBinary(r.ReadInt64()),
                SpeedLimit = r.ReadInt32(),
                Url = new Uri(r.ReadString())
            };
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }

            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = XDM.Messaging.StreamHelper.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = XDM.Messaging.StreamHelper.ReadString(r),
                    Password = XDM.Messaging.StreamHelper.ReadString(r),
                };
            }
            state.ConvertToMp3 = r.ReadBoolean();
            return state;
        }

        public static void Save(SingleSourceHTTPDownloaderState state)
        {
            TransactedBinaryDataReader.Write($"{state.Id}.state", Config.DataDir, w => StateToBytes(state, w));
        }

        private static void StateToBytes(SingleSourceHTTPDownloaderState state, BinaryWriter w)
        {
            w.Write(state!.Id!);
            w.Write(state.TempDir ?? string.Empty);
            w.Write(state.FileSize);
            w.Write(state.LastModified.ToBinary());
            w.Write(state.SpeedLimit);
            w.Write(state.Url.ToString());
            var hasHeaders = state.Headers != null;
            w.Write(hasHeaders);
            if (hasHeaders)
            {
                XDM.Messaging.StreamHelper.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                XDM.Messaging.StreamHelper.WriteStateCookies(state.Cookies, w);
            }
            var hasProxy = state.Proxy.HasValue;
            w.Write(hasProxy);
            if (hasProxy)
            {
                w.Write(state.Proxy!.Value.Host ?? string.Empty);
                w.Write(state.Proxy!.Value.Port);
                w.Write((int)state.Proxy!.Value.ProxyType);
                w.Write(state.Proxy!.Value.UserName ?? string.Empty);
                w.Write(state.Proxy!.Value.Password ?? string.Empty);
            }
            w.Write(state.ConvertToMp3);
        }

        public static DualSourceHTTPDownloaderState LoadDualSourceHTTPDownloaderState(string id)
        {
            DualSourceHTTPDownloaderState? state = null;
            TransactedBinaryDataReader.Read($"{id}.state", Config.DataDir, r =>
            {
                state = DualSourceHTTPDownloaderStateFromBytes(r);
            });
            if (state == null)
            {
                throw new IOException("Unable to read state: " + id);
            }
            return state;
        }

        private static DualSourceHTTPDownloaderState DualSourceHTTPDownloaderStateFromBytes(BinaryReader r)
        {
            var state = new DualSourceHTTPDownloaderState
            {
                Id = r.ReadString(),
                TempDir = XDM.Messaging.StreamHelper.ReadString(r),
                FileSize = r.ReadInt64(),
                LastModified = DateTime.FromBinary(r.ReadInt64()),
                SpeedLimit = r.ReadInt32(),
                Init1 = r.ReadBoolean(),
                Init2 = r.ReadBoolean(),
                Url1 = new Uri(r.ReadString()),
                Url2 = new Uri(r.ReadString()),
            };
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers1 = headers;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers2 = headers;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies1 = cookies;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies2 = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = XDM.Messaging.StreamHelper.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = XDM.Messaging.StreamHelper.ReadString(r),
                    Password = XDM.Messaging.StreamHelper.ReadString(r),
                };
            }
            return state;
        }

        public static void Save(DualSourceHTTPDownloaderState state)
        {
            TransactedBinaryDataReader.Write($"{state.Id}.state", Config.DataDir, w => StateToBytes(state, w));
        }

        private static void StateToBytes(DualSourceHTTPDownloaderState state, BinaryWriter w)
        {
            w.Write(state.Id!);
            w.Write(state.TempDir ?? string.Empty);
            w.Write(state.FileSize);
            w.Write(state.LastModified.ToBinary());
            w.Write(state.SpeedLimit);
            w.Write(state.Init1);
            w.Write(state.Init2);
            w.Write(state.Url1.ToString());
            w.Write(state.Url2.ToString());
            var hasHeaders1 = state.Headers1 != null;
            w.Write(hasHeaders1);
            if (hasHeaders1)
            {
                XDM.Messaging.StreamHelper.WriteStateHeaders(state.Headers1, w);
            }
            var hasHeaders2 = state.Headers2 != null;
            w.Write(hasHeaders2);
            if (hasHeaders2)
            {
                XDM.Messaging.StreamHelper.WriteStateHeaders(state.Headers2, w);
            }
            var hasCookies1 = state.Cookies1 != null;
            w.Write(hasCookies1);
            if (hasCookies1)
            {
                XDM.Messaging.StreamHelper.WriteStateCookies(state.Cookies1, w);
            }
            var hasCookies2 = state.Cookies2 != null;
            w.Write(hasCookies2);
            if (hasCookies2)
            {
                XDM.Messaging.StreamHelper.WriteStateCookies(state.Cookies2, w);
            }
            var hasProxy = state.Proxy.HasValue;
            w.Write(hasProxy);
            if (hasProxy)
            {
                w.Write(state.Proxy!.Value.Host ?? string.Empty);
                w.Write(state.Proxy!.Value.Port);
                w.Write((int)state.Proxy!.Value.ProxyType);
                w.Write(state.Proxy!.Value.UserName ?? string.Empty);
                w.Write(state.Proxy!.Value.Password ?? string.Empty);
            }
        }

        public static void Save(MultiSourceDASHDownloadState state)
        {
            TransactedBinaryDataReader.Write($"{state.Id}.state", Config.DataDir, w => StateToBytes(state, w));
        }

        private static void StateToBytes(MultiSourceDASHDownloadState state, BinaryWriter w)
        {
            w.Write(state.Id);
            w.Write(state.TempDirectory ?? string.Empty);
            w.Write(state.FileSize);
            w.Write(state.Demuxed);
            w.Write(state.SpeedLimit);
            w.Write(state.AudioChunkCount);
            w.Write(state.AudioContainerFormat ?? string.Empty);
            w.Write(state.VideoChunkCount);
            w.Write(state.VideoContainerFormat ?? string.Empty);
            w.Write(state.Duration);
            w.Write(state.Url ?? string.Empty);
            w.Write(state.AudioSegments?.Count ?? 0);
            for (var i = 0; i < (state.AudioSegments?.Count ?? 0); i++)
            {
                w.Write(state.AudioSegments![i].ToString());
            }
            w.Write(state.VideoSegments?.Count ?? 0);
            for (var i = 0; i < (state.VideoSegments?.Count ?? 0); i++)
            {
                w.Write(state.VideoSegments![i].ToString());
            }
            var hasHeaders = state.Headers != null;
            w.Write(hasHeaders);
            if (hasHeaders)
            {
                XDM.Messaging.StreamHelper.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                XDM.Messaging.StreamHelper.WriteStateCookies(state.Cookies, w);
            }
            var hasProxy = state.Proxy.HasValue;
            w.Write(hasProxy);
            if (hasProxy)
            {
                w.Write(state.Proxy!.Value.Host ?? string.Empty);
                w.Write(state.Proxy!.Value.Port);
                w.Write((int)state.Proxy!.Value.ProxyType);
                w.Write(state.Proxy!.Value.UserName ?? string.Empty);
                w.Write(state.Proxy!.Value.Password ?? string.Empty);
            }
        }

        public static MultiSourceDASHDownloadState LoadMultiSourceDASHDownloadState(string id)
        {
            MultiSourceDASHDownloadState? state = null;
            TransactedBinaryDataReader.Read($"{id}.state", Config.DataDir, r =>
            {
                state = MultiSourceDASHDownloadStateFromBytes(r);
            });
            if (state == null)
            {
                throw new IOException("Unable to read state: " + id);
            }
            return state;
        }

        private static MultiSourceDASHDownloadState MultiSourceDASHDownloadStateFromBytes(BinaryReader r)
        {
            var state = new MultiSourceDASHDownloadState
            {
                Id = r.ReadString(),
                TempDirectory = XDM.Messaging.StreamHelper.ReadString(r),
                FileSize = r.ReadInt64(),
                Demuxed = r.ReadBoolean(),
                SpeedLimit = r.ReadInt32(),
                AudioChunkCount = r.ReadInt32(),
                AudioContainerFormat = XDM.Messaging.StreamHelper.ReadString(r),
                VideoChunkCount = r.ReadInt32(),
                VideoContainerFormat = XDM.Messaging.StreamHelper.ReadString(r),
                Duration = r.ReadDouble(),
                Url = XDM.Messaging.StreamHelper.ReadString(r)
            };

            var ac = r.ReadInt32();
            if (ac != 0)
            {
                state.AudioSegments = new(ac);
                for (var i = 0; i < ac; i++)
                {
                    state.AudioSegments.Add(new Uri(r.ReadString()));
                }
            }

            var vc = r.ReadInt32();
            if (vc != 0)
            {
                state.VideoSegments = new(vc);
                for (var i = 0; i < vc; i++)
                {
                    state.VideoSegments.Add(new Uri(r.ReadString()));
                }
            }

            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = XDM.Messaging.StreamHelper.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = XDM.Messaging.StreamHelper.ReadString(r),
                    Password = XDM.Messaging.StreamHelper.ReadString(r),
                };
            }
            return state;
        }

        public static void Save(MultiSourceHLSDownloadState state)
        {
            TransactedBinaryDataReader.Write($"{state.Id}.state", Config.DataDir, w => StateToBytes(state, w));
        }

        private static void StateToBytes(MultiSourceHLSDownloadState state, BinaryWriter w)
        {
            w.Write(state.Id);
            w.Write(state.TempDirectory ?? string.Empty);
            w.Write(state.FileSize);
            w.Write(state.Demuxed);
            w.Write(state.SpeedLimit);
            w.Write(state.AudioChunkCount);
            w.Write(state.AudioContainerFormat ?? string.Empty);
            w.Write(state.VideoChunkCount);
            w.Write(state.VideoContainerFormat ?? string.Empty);
            w.Write(state.Duration);
            w.Write(state.MuxedPlaylistUrl?.ToString() ?? string.Empty);
            w.Write(state.NonMuxedAudioPlaylistUrl?.ToString() ?? string.Empty);
            w.Write(state.NonMuxedVideoPlaylistUrl?.ToString() ?? string.Empty);
            var hasHeaders = state.Headers != null;
            w.Write(hasHeaders);
            if (hasHeaders)
            {
                XDM.Messaging.StreamHelper.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                XDM.Messaging.StreamHelper.WriteStateCookies(state.Cookies, w);
            }
            var hasProxy = state.Proxy.HasValue;
            w.Write(hasProxy);
            if (hasProxy)
            {
                w.Write(state.Proxy!.Value.Host ?? string.Empty);
                w.Write(state.Proxy!.Value.Port);
                w.Write((int)state.Proxy!.Value.ProxyType);
                w.Write(state.Proxy!.Value.UserName ?? string.Empty);
                w.Write(state.Proxy!.Value.Password ?? string.Empty);
            }
        }

        public static MultiSourceHLSDownloadState LoadMultiSourceHLSDownloadState(string id)
        {
            MultiSourceHLSDownloadState? state = null;
            TransactedBinaryDataReader.Read($"{id}.state", Config.DataDir, r =>
            {
                state = MultiSourceHLSDownloadStateFromBytes(r);
            });
            if (state == null)
            {
                throw new IOException("Unable to read state: " + id);
            }
            return state;
        }

        private static MultiSourceHLSDownloadState MultiSourceHLSDownloadStateFromBytes(BinaryReader r)
        {
            var state = new MultiSourceHLSDownloadState
            {
                Id = r.ReadString(),
                TempDirectory = XDM.Messaging.StreamHelper.ReadString(r),
                FileSize = r.ReadInt64(),
                Demuxed = r.ReadBoolean(),
                SpeedLimit = r.ReadInt32(),
                AudioChunkCount = r.ReadInt32(),
                AudioContainerFormat = XDM.Messaging.StreamHelper.ReadString(r),
                VideoChunkCount = r.ReadInt32(),
                VideoContainerFormat = XDM.Messaging.StreamHelper.ReadString(r),
                Duration = r.ReadDouble()
            };

            var muxedPlaylistUrl = XDM.Messaging.StreamHelper.ReadString(r);
            var nonMuxedAudioPlaylistUrl = XDM.Messaging.StreamHelper.ReadString(r);
            var nonMuxedVideoPlaylistUrl = XDM.Messaging.StreamHelper.ReadString(r);

            if (muxedPlaylistUrl != null)
            {
                state.MuxedPlaylistUrl = new Uri(muxedPlaylistUrl);
            }
            if (nonMuxedAudioPlaylistUrl != null)
            {
                state.NonMuxedAudioPlaylistUrl = new Uri(nonMuxedAudioPlaylistUrl);
            }
            if (nonMuxedVideoPlaylistUrl != null)
            {
                state.NonMuxedVideoPlaylistUrl = new Uri(nonMuxedVideoPlaylistUrl);
            }

            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                XDM.Messaging.StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = XDM.Messaging.StreamHelper.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = XDM.Messaging.StreamHelper.ReadString(r),
                    Password = XDM.Messaging.StreamHelper.ReadString(r),
                };
            }
            return state;
        }
    }
}
