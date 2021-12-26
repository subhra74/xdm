using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader.Adaptive.Dash;
using XDM.Core.Lib.Downloader.Adaptive.Hls;
using XDM.Core.Lib.Downloader.Progressive.DualHttp;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Util;

namespace XDM.Core.Lib.Downloader
{
    public static class DownloadStateStore
    {
        public static SingleSourceHTTPDownloaderState SingleSourceHTTPDownloaderStateFromBytes(byte[] bytes)
        {
            var r = new BinaryReader(new MemoryStream(bytes));
            var state = new SingleSourceHTTPDownloaderState
            {
                Id = r.ReadString(),
                TempDir = Helpers.ReadString(r),
                FileSize = r.ReadInt64(),
                LastModified = DateTime.FromBinary(r.ReadInt64()),
                SpeedLimit = r.ReadInt32(),
                Url = new Uri(r.ReadString())
            };
            if (r.ReadBoolean())
            {
                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }

            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = Helpers.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = Helpers.ReadString(r),
                    Password = Helpers.ReadString(r),
                };
            }
            return state;
        }

        public static byte[] StateToBytes(SingleSourceHTTPDownloaderState state)
        {
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            w.Write(state.Id);
            w.Write(state.TempDir ?? string.Empty);
            w.Write(state.FileSize);
            w.Write(state.LastModified.ToBinary());
            w.Write(state.SpeedLimit);
            w.Write(state.Url.ToString());
            var hasHeaders = state.Headers != null;
            w.Write(hasHeaders);
            if (hasHeaders)
            {
                Helpers.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                Helpers.WriteStateCookies(state.Cookies, w);
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
            return ms.ToArray();
        }

        public static DualSourceHTTPDownloaderState DualSourceHTTPDownloaderStateFromBytes(byte[] bytes)
        {
            var r = new BinaryReader(new MemoryStream(bytes));
            var state = new DualSourceHTTPDownloaderState
            {
                Id = r.ReadString(),
                TempDir = Helpers.ReadString(r),
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
                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers1 = headers;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers2 = headers;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies1 = cookies;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies2 = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = Helpers.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = Helpers.ReadString(r),
                    Password = Helpers.ReadString(r),
                };
            }
            return state;
        }
        public static byte[] StateToBytes(DualSourceHTTPDownloaderState state)
        {
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            w.Write(state.Id);
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
                Helpers.WriteStateHeaders(state.Headers1, w);
            }
            var hasHeaders2 = state.Headers2 != null;
            w.Write(hasHeaders2);
            if (hasHeaders2)
            {
                Helpers.WriteStateHeaders(state.Headers2, w);
            }
            var hasCookies1 = state.Cookies1 != null;
            w.Write(hasCookies1);
            if (hasCookies1)
            {
                Helpers.WriteStateCookies(state.Cookies1, w);
            }
            var hasCookies2 = state.Cookies2 != null;
            w.Write(hasCookies2);
            if (hasCookies2)
            {
                Helpers.WriteStateCookies(state.Cookies2, w);
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
            return ms.ToArray();
        }

        public static byte[] StateToBytes(MultiSourceDASHDownloadState state)
        {
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
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
                Helpers.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                Helpers.WriteStateCookies(state.Cookies, w);
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
            return ms.ToArray();
        }

        public static MultiSourceDASHDownloadState MultiSourceDASHDownloadStateFromBytes(byte[] bytes)
        {
            var r = new BinaryReader(new MemoryStream(bytes));
            var state = new MultiSourceDASHDownloadState
            {
                Id = r.ReadString(),
                TempDirectory = Helpers.ReadString(r),
                FileSize = r.ReadInt64(),
                Demuxed = r.ReadBoolean(),
                SpeedLimit = r.ReadInt32(),
                AudioChunkCount = r.ReadInt32(),
                AudioContainerFormat = Helpers.ReadString(r),
                VideoChunkCount = r.ReadInt32(),
                VideoContainerFormat = Helpers.ReadString(r),
                Duration = r.ReadDouble(),
                Url = Helpers.ReadString(r)
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
                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = Helpers.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = Helpers.ReadString(r),
                    Password = Helpers.ReadString(r),
                };
            }
            return state;
        }

        public static byte[] StateToBytes(MultiSourceHLSDownloadState state)
        {
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
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
                Helpers.WriteStateHeaders(state.Headers, w);
            }
            var hasCookies = state.Cookies != null;
            w.Write(hasCookies);
            if (hasCookies)
            {
                Helpers.WriteStateCookies(state.Cookies, w);
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
            return ms.ToArray();
        }

        public static MultiSourceHLSDownloadState MultiSourceHLSDownloadStateFromBytes(byte[] bytes)
        {
            var r = new BinaryReader(new MemoryStream(bytes));
            var state = new MultiSourceHLSDownloadState
            {
                Id = r.ReadString(),
                TempDirectory = Helpers.ReadString(r),
                FileSize = r.ReadInt64(),
                Demuxed = r.ReadBoolean(),
                SpeedLimit = r.ReadInt32(),
                AudioChunkCount = r.ReadInt32(),
                AudioContainerFormat = Helpers.ReadString(r),
                VideoChunkCount = r.ReadInt32(),
                VideoContainerFormat = Helpers.ReadString(r),
                Duration = r.ReadDouble()
            };

            var muxedPlaylistUrl = Helpers.ReadString(r);
            var nonMuxedAudioPlaylistUrl = Helpers.ReadString(r);
            var nonMuxedVideoPlaylistUrl = Helpers.ReadString(r);

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
                Helpers.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                state.Headers = headers;
            }
            if (r.ReadBoolean())
            {
                Helpers.ReadStateCookies(r, out Dictionary<string, string> cookies);
                state.Cookies = cookies;
            }
            if (r.ReadBoolean())
            {
                state.Proxy = new ProxyInfo
                {
                    Host = Helpers.ReadString(r),
                    Port = r.ReadInt32(),
                    ProxyType = (ProxyType)r.ReadInt32(),
                    UserName = Helpers.ReadString(r),
                    Password = Helpers.ReadString(r),
                };
            }
            return state;
        }
    }
}
