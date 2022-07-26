using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using TraceLog;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Messaging;

namespace XDM.Core.IO
{
    public static class RequestDataIO
    {
        public static void WriteStringSafe(string? text, BinaryWriter w)
        {
            w.Write(text ?? string.Empty);
        }

        public static void SaveDownloadInfo(string id, SingleSourceHTTPDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.Uri, w);
            WriteStringSafe(info.File, w);
            w.Write(info.ContentLength);
            StreamHelper.WriteStateHeaders(info.Headers, w);
            StreamHelper.WriteStateCookies(info.Cookies, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static void SaveDownloadInfo(string id, DualSourceHTTPDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.Uri1, w);
            WriteStringSafe(info.Uri2, w);
            WriteStringSafe(info.File, w);
            w.Write(info.ContentLength);
            StreamHelper.WriteStateHeaders(info.Headers1, w);
            StreamHelper.WriteStateHeaders(info.Headers2, w);
            StreamHelper.WriteStateCookies(info.Cookies1, w);
            StreamHelper.WriteStateCookies(info.Cookies2, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static void SaveDownloadInfo(string id, MultiSourceHLSDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.VideoUri, w);
            WriteStringSafe(info.AudioUri, w);
            WriteStringSafe(info.File, w);
            StreamHelper.WriteStateHeaders(info.Headers, w);
            StreamHelper.WriteStateCookies(info.Cookies, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static void SaveDownloadInfo(string id, MultiSourceDASHDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.Url, w);
            WriteStringSafe(info.File, w);
            WriteStringSafe(info.AudioFormat, w);
            WriteStringSafe(info.VideoFormat, w);
            WriteStringSafe(info.AudioMimeType, w);
            WriteStringSafe(info.VideoMimeType, w);
            w.Write(info.Duration);
            StreamHelper.WriteStateHeaders(info.Headers, w);
            StreamHelper.WriteStateCookies(info.Cookies, w);
            var c1 = info.AudioSegments == null ? 0 : info.AudioSegments.Count;
            if (c1 > 0)
            {
                foreach (var audioSegment in info.AudioSegments!)
                {
                    w.Write(audioSegment.ToString());
                }
            }
            var c2 = info.VideoSegments == null ? 0 : info.VideoSegments.Count;
            if (c1 > 0)
            {
                foreach (var videoSegment in info.VideoSegments!)
                {
                    w.Write(videoSegment.ToString());
                }
            }
        }

        public static SingleSourceHTTPDownloadInfo? LoadSingleSourceHTTPDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new SingleSourceHTTPDownloadInfo
                {
                    Uri = StreamHelper.ReadString(r),
                    File = StreamHelper.ReadString(r),
                    ContentLength = r.ReadInt64()
                };
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                info.Cookies = cookies;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }

        public static DualSourceHTTPDownloadInfo? LoadDualSourceHTTPDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new DualSourceHTTPDownloadInfo
                {
                    Uri1 = StreamHelper.ReadString(r),
                    Uri2 = StreamHelper.ReadString(r),
                    File = StreamHelper.ReadString(r),
                    ContentLength = r.ReadInt64()
                };
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers1);
                info.Headers1 = headers1;
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers2);
                info.Headers2 = headers2;
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies1);
                info.Cookies1 = cookies1;
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies2);
                info.Cookies2 = cookies2;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }

        public static MultiSourceHLSDownloadInfo? LoadMultiSourceHLSDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new MultiSourceHLSDownloadInfo
                {
                    VideoUri = StreamHelper.ReadString(r),
                    AudioUri = StreamHelper.ReadString(r),
                    File = StreamHelper.ReadString(r)
                };
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                info.Cookies = cookies;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }

        public static MultiSourceDASHDownloadInfo? LoadMultiSourceDASHDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new MultiSourceDASHDownloadInfo
                {
                    Url = StreamHelper.ReadString(r),
                    File = StreamHelper.ReadString(r),
                    AudioFormat = StreamHelper.ReadString(r),
                    VideoFormat = StreamHelper.ReadString(r),
                    AudioMimeType = StreamHelper.ReadString(r),
                    VideoMimeType = StreamHelper.ReadString(r),
                    Duration = r.ReadInt64()
                };
                StreamHelper.ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                StreamHelper.ReadStateCookies(r, out Dictionary<string, string> cookies);
                info.Cookies = cookies;

                var c1 = r.ReadInt32();
                if (c1 > 0)
                {
                    info.AudioSegments = new List<Uri>(c1);
                    for (int c = 0; c < c1; c++)
                    {
                        info.AudioSegments.Add(new Uri(r.ReadString()));
                    }
                }

                var c2 = r.ReadInt32();
                if (c2 > 0)
                {
                    info.VideoSegments = new List<Uri>(c2);
                    for (int c = 0; c < c2; c++)
                    {
                        info.VideoSegments.Add(new Uri(r.ReadString()));
                    }
                }
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }
    }
}
