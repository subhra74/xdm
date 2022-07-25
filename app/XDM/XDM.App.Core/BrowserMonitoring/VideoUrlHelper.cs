using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core;
using XDM.Core.Util;
//using XDM.Core.Downloader.YT.Dash;
using System.IO;
using MediaParser.Hls;
using MediaParser.Dash;
using MediaParser.YouTube;
using System.Security.Cryptography;
using TraceLog;
using XDM.Core.Clients.Http;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Adaptive.Hls;

#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

namespace BrowserMonitoring
{
    static class VideoUrlHelper
    {
        private static object lockObject = new object();
        private static DashInfo lastVid;
        private static List<DashInfo> videoQueue = new();
        private static List<DashInfo> audioQueue = new();
        private static Dictionary<string, DateTime> referersToSkip = new();  //Skip the video requests whose referer hash is present in below dict
                                                                             //as they were triggered by HLS or DASH 

        internal static bool IsNormalVideo(string contentType, string url, long size)
        {
            if (size > 0 && size < Config.Instance.MinVideoSize * 1024)
            {
                return false;
            }
            return (contentType != null && !(contentType.Contains("f4f") ||
                                contentType.Contains("m4s") ||
                                contentType.Contains("mp2t") || url.Contains("abst") ||
                                url.Contains("f4x") || url.Contains(".fbcdn")
                                || url.Contains("http://127.0.0.1:9614")));
        }

        internal static void ProcessPostYtFormats(Message message, IAppService app)
        {
            //var file = message.File ?? Helpers.GetFileName(new Uri(message.Url));
            var manifest = DownloadManifest(message);
            if (manifest == null)
            {
                Log.Debug("Failed to download youtube manifest: " + message.Url);
                return;
            }
            //var manifestText = File.ReadAllText(manifest);
            //Log.Debug("Text: {text}", manifestText);

            try
            {
                var (DualVideoItems, VideoItems) = YoutubeDataFormatParser.GetFormats(manifest);
                Log.Debug("DualVideoItems: " + DualVideoItems.Count + " VideoItems: " + VideoItems.Count);
                message.RequestHeaders.Remove("Content-Type");

                if (DualVideoItems != null && DualVideoItems.Count > 0)
                {
                    lock (app)
                    {
                        var list = new List<(DualSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)>();
                        foreach (var item in DualVideoItems)
                        {
                            var fileExt = item.MediaContainer;
                            var mediaItem = new DualSourceHTTPDownloadInfo
                            {
                                Uri1 = item.VideoUrl,
                                Uri2 = item.AudioUrl,
                                Headers1 = message.RequestHeaders,
                                Headers2 = message.RequestHeaders,
                                File = Helpers.SanitizeFileName(item.Title) + "." + fileExt,
                                Cookies1 = message.Cookies,
                                Cookies2 = message.Cookies
                            };

                            var size = item.Size > 0 ? Helpers.FormatSize(item.Size) : string.Empty;
                            var displayInfo = new StreamingVideoDisplayInfo
                            {
                                Quality = $"[{fileExt.ToUpperInvariant()}] {size} {item.FormatDescription}",
                                Size = item.Size,
                                CreationTime = DateTime.Now
                            };

                            //var displayText = $"[{fileExt.ToUpperInvariant()}] {size} {item.FormatDescription}";
                            list.Add((Info: mediaItem, DisplayInfo: displayInfo));
                            //app.AddVideoNotification(displayText, mediaItem);
                        }
                        app.AddVideoNotifications(list);
                    }
                }
                if (VideoItems != null && VideoItems.Count > 0)
                {
                    lock (app)
                    {
                        var list = new List<(SingleSourceHTTPDownloadInfo Info, StreamingVideoDisplayInfo DisplayInfo)>();
                        foreach (var item in VideoItems)
                        {
                            var fileExt = item.MediaContainer;
                            var mediaItem = new SingleSourceHTTPDownloadInfo
                            {
                                Uri = item.MediaUrl,
                                Headers = message.RequestHeaders,
                                File = Helpers.SanitizeFileName(item.Title) + "." + fileExt,
                                Cookies = message.Cookies
                            };
                            var size = item.Size > 0 ? Helpers.FormatSize(item.Size) : string.Empty;
                            var displayText = $"[{fileExt.ToUpperInvariant()}] {size} {item.FormatDescription}";

                            var displayInfo = new StreamingVideoDisplayInfo
                            {
                                Quality = $"[{fileExt.ToUpperInvariant()}] {size} {item.FormatDescription}",
                                Size = item.Size,
                                CreationTime = DateTime.Now
                            };

                            list.Add((Info: mediaItem, DisplayInfo: displayInfo));
                        }
                        app.AddVideoNotifications(list);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Failed to parse youtube manifest");
            }
        }

        internal static void ProcessDashVideo(Message message, IAppService app)
        {
            var file = message.File ?? Helpers.GetFileName(new Uri(message.Url));
            Log.Debug("Downloading MPD manifest: " + message.Url);

            AddToSkippedRefererList(message.GetRequestHeaderFirstValue("Referer"));

            var manifest = DownloadManifest(message);
            if (manifest == null) { return; }
            var manifestText = File.ReadAllText(manifest);
            Log.Debug("MPD playlist");
            var mediaEntries = MpdParser.Parse(manifestText.Split('\n'), message.Url);
            if (mediaEntries.Count < 1) return;

            Log.Debug("Manifest contains: " + mediaEntries.Count);
            var count = 0;
            foreach (var plc in mediaEntries)
            {
                foreach ((Representation video, Representation audio) in plc)
                {
                    //prefix added for multi period
                    var prefix = (count == 0 ? "" : count.ToString() + " ");

                    if (video != null && audio != null)
                    {
                        if (video.Segments.Count == 1 && audio.Segments.Count == 1)
                        {
                            Log.Debug("DASH manifest contains 1 audio and 1 video, making it DualSourceHTTPDownload");
                            var fileExt = (((video.MimeType + "").Contains("mp4") && (audio.MimeType + "").Contains("mp4")) ? "mp4" : "mkv");
                            var mediaItem = new DualSourceHTTPDownloadInfo
                            {
                                Uri1 = video.Segments[0].ToString(),
                                Uri2 = audio.Segments[0].ToString(),
                                Headers1 = message.RequestHeaders,
                                Headers2 = message.RequestHeaders,
                                File = prefix + Helpers.SanitizeFileName(file) + "." + fileExt,
                                Cookies1 = message.Cookies,
                                Cookies2 = message.Cookies
                            };
                            var displayText = $"[{fileExt.ToUpperInvariant()}] {GetQualityString(video, audio)}";
                            Log.Debug("Display text dash: " + displayText);
                            app.AddVideoNotification(new StreamingVideoDisplayInfo
                            {
                                Quality = displayText,
                                CreationTime = DateTime.Now
                            }, mediaItem);
                        }
                        else
                        {
                            var fileExt = (((video.MimeType + "").Contains("mp4") && (audio.MimeType + "").Contains("mp4")) ? "mp4" : "mkv");
                            var mediaItem = new MultiSourceDASHDownloadInfo
                            {
                                VideoSegments = video.Segments,
                                AudioSegments = audio.Segments,
                                Headers = message.RequestHeaders,
                                File = prefix + Helpers.SanitizeFileName(file) + "." + fileExt,
                                Cookies = message.Cookies,
                                Duration = Math.Max(video.Duration, audio.Duration),
                                Url = message.Url,
                                VideoMimeType = video.MimeType,
                                AudioMimeType = audio.MimeType
                            };
                            var displayText = $"[{fileExt.ToUpperInvariant()}] {GetQualityString(video, audio)}";
                            Log.Debug("Display text hls: " + displayText);
                            app.AddVideoNotification(new StreamingVideoDisplayInfo
                            {
                                Quality = displayText,
                                CreationTime = DateTime.Now
                            }, mediaItem);
                        }
                    }
                    else if (video != null)
                    {
                        Log.Debug("DASH manifest contains no audio and 1 video, making it SingleSourceHTTPDownload");
                        AddSingleItem(video, message, app, prefix, false, file);
                    }
                    else if (audio != null)
                    {
                        Log.Debug("DASH manifest contains 1 audio and no video, making it SingleSourceHTTPDownload");
                        AddSingleItem(audio, message, app, prefix, true, file);
                    }
                    else
                    {
                        Log.Debug("No audio or video in dash mpd");
                    }
                }
                count++;
            }
        }

        private static void AddSingleItem(Representation item, Message message, IAppService app, string prefix, bool audio, string file)
        {
            var fileExt = (item.MimeType + "").Contains("mp4") ? "mp4" : "mkv";
            var mediaItem = new SingleSourceHTTPDownloadInfo
            {
                Uri = item.Segments[0].ToString(),
                Headers = message.RequestHeaders,
                File = prefix + Helpers.SanitizeFileName(file) + "." + fileExt,
                Cookies = message.Cookies
            };
            var quality = audio ? GetQualityString(null, item) : GetQualityString(item, null);
            var displayText = $"[{fileExt.ToUpperInvariant()}] {quality}";
            Log.Debug("Display text hls: " + displayText);
            app.AddVideoNotification(new StreamingVideoDisplayInfo
            {
                Quality = displayText,
                CreationTime = DateTime.Now
            }, mediaItem);
        }

        private static string GetQualityString(Representation video, Representation audio)
        {
            string GetVideoResolution(Representation video)
            {
                return video.Height > 0 ? video.Height + "p " : "";
            }
            string GetAudioLanguage(Representation audio)
            {
                return audio.Language != null && audio.Language != "und" ? audio.Language + " " : "";
            }
            string GetBandwidth(params Representation[] args)
            {
                var sum = 0L;
                foreach (var arg in args)
                {
                    if (arg != null && arg.Bandwidth > 0) sum += arg.Bandwidth;
                }
                return sum > 0 ? (sum / 1024) + " Kbps " : "";
            }
            var text = new StringBuilder();
            if (video != null && audio != null)
            {
                text.Append(GetVideoResolution(video) + GetBandwidth(video, audio) + " " + GetAudioLanguage(audio));
            }
            else if (video != null)
            {
                text.Append(GetVideoResolution(video) + GetBandwidth(video, audio));
            }
            else if (audio != null)
            {
                text.Append(GetAudioLanguage(audio) + GetBandwidth(video, audio));
            }
            return text.ToString();
        }

        internal static void ProcessHLSVideo(Message message, IAppService app)
        {
            Log.Debug("Downloading HLS manifest: " + message.Url);

            AddToSkippedRefererList(message.GetRequestHeaderFirstValue("Referer"));

            var manifest = DownloadManifest(message);
            if (manifest != null)
            {
                var manifestText = File.ReadAllText(manifest);
                if (manifestText.Contains(HlsParser.EXT_X_STREAM_INF))
                {
                    Log.Debug("Master playlist: " + message.Url);
                    var playlists = HlsParser.ParseMasterPlaylist(manifestText.Split('\n'), message.Url);
                    if (playlists != null && playlists.Count > 0)
                    {
                        Log.Debug("Master playlist contains: " + playlists.Count);
                        foreach (var plc in playlists)
                        {
                            var type = (plc.AudioPlaylist != null && plc.VideoPlaylist != null ? "MP4" : "TS");
                            var video = new MultiSourceHLSDownloadInfo
                            {
                                VideoUri = plc.VideoPlaylist?.ToString(),
                                AudioUri = plc.AudioPlaylist?.ToString(),
                                Headers = message.RequestHeaders,
                                File = Helpers.SanitizeFileName(message.File ?? (Helpers.GetFileName(new Uri(message.Url)))) +
                                (plc.AudioPlaylist != null && plc.VideoPlaylist != null ?
                                "." + type.ToLowerInvariant() : "." + type.ToLowerInvariant()),
                                Cookies = message.Cookies
                            };

                            var displayText = $"{type} {plc.Quality}";
                            Log.Debug("Display text hls: " + plc.Quality);
                            app.AddVideoNotification(new StreamingVideoDisplayInfo
                            {
                                Quality = displayText,
                                CreationTime = DateTime.Now
                            }, video);
                        }
                    }
                }
                else
                {
                    if (manifestText.Contains(HlsParser.EXT_X_I_FRAMES_ONLY))
                    {
                        Log.Debug("Skipping EXT_X_I_FRAMES_ONLY: " + message.Url);
                        return;
                    }
                    Log.Debug("Not Master playlist");
                    var mediaPlaylist = HlsParser.ParseMediaSegments(manifestText.Split('\n'), message.Url);
                    if (mediaPlaylist == null) return;
                    var file = Helpers.GetFileName(mediaPlaylist.MediaSegments.Last().Url);
                    var container = Helpers.GuessContainerFormatFromSegmentExtension(Path.GetExtension(file));
                    var video = new MultiSourceHLSDownloadInfo
                    {
                        VideoUri = message.Url,
                        Headers = message.RequestHeaders,
                        File = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url))) + ".ts",
                        Cookies = message.Cookies,
                    };
                    var displayText = $"[{container}]";
                    app.AddVideoNotification(new StreamingVideoDisplayInfo
                    {
                        Quality = displayText,
                        CreationTime = DateTime.Now
                    }, video);
                }
            }
        }

        public static bool ProcessYtDashSegment(Message message, IAppService app)
        {
            try
            {
                var url = new Uri(message.Url);
                if (!(url.Host.Contains("youtube.com") || url.Host.Contains("googlevideo.com")))
                {
                    return false;
                }
                var contentType = message.GetResponseHeaderValue("Content-Type")?[0]?.ToLowerInvariant();
                if (!(contentType != null && (contentType.Contains("audio/") ||
                    contentType.Contains("video/") ||
                    contentType.Contains("application/octet"))))
                {
                    return false;
                }
                var lowUrl = message.Url.ToLowerInvariant();
                if (!(lowUrl.Contains("videoplayback") && lowUrl.Contains("itag")))
                {
                    return false;
                }
                (var path, var query, _) = Helpers.ParseKeyValuePair(message.Url, '?');

                string[] arr = query.Split('&');
                var yt_url = new StringBuilder();
                yt_url.Append(path + "?");
                int itag = 0;
                long clen = 0;
                String id = "";
                String mime = "";

                for (int i = 0; i < arr.Length; i++)
                {
                    var str = arr[i];
                    (var key, var val, var success) = Helpers.ParseKeyValuePair(str, '=');
                    if (!success)
                    {
                        continue;
                    }

                    if (key.StartsWith("range"))
                    {
                        continue;
                    }

                    if (key == "itag")
                    {
                        itag = Int32.Parse(val);
                    }

                    if (key == "clen")
                    {
                        clen = Int64.Parse(val);
                    }

                    if (key.StartsWith("mime"))
                    {
                        mime = Uri.UnescapeDataString(val);
                    }

                    if (str.StartsWith("id"))
                    {
                        id = val;
                    }

                    yt_url.Append(str);
                    if (i < arr.Length - 1)
                    {
                        yt_url.Append('&');
                    }
                }
                if (itag != 0 && IsNormalVideo(itag))
                {
                    return false;
                }

                var info = new DashInfo()
                {
                    Url = yt_url.ToString(),
                    Length = clen,
                    IsVideo = mime.StartsWith("video"),
                    ITag = itag,
                    ID = id,
                    Mime = mime,
                    Headers = message.RequestHeaders,
                    Cookies = message.Cookies
                };

                if (AddToQueue(info))
                {
                    if (!info.IsVideo && mime.StartsWith("audio/"))
                    {
                        HandleDashAudio(info, app, message);
                    }
                    var di = GetDASHPair(info);
                    if (di == null)
                    {
                        return true;
                    }
                    var video = CreateDualSourceHTTPDownloadInfo(di, info, message);
                    //    new DualSourceHTTPDownloadInfo
                    //{
                    //    Uri1 = di.Url,
                    //    Uri2 = info.Url,
                    //    Headers1 = di.Headers,
                    //    Headers2 = info.Headers,
                    //    File = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url))) + ".mkv",
                    //    Cookies1 = di.Cookies,
                    //    Cookies2 = info.Cookies,
                    //    ContentLength = di.Length + info.Length
                    //};

                    var size = di.Length + info.Length;
                    Log.Debug("Itag: " + info.ITag + " " + di.ITag);
                    var quality = Itags.GetValueOrDefault(info.IsVideo ? info.ITag : di.ITag, "MKV");

                    var displayText = $"[{quality}] {(size > 0 ? Helpers.FormatSize(size) : string.Empty)}";
                    app.AddVideoNotification(new StreamingVideoDisplayInfo
                    {
                        Quality = displayText,
                        Size = size,
                        CreationTime = DateTime.Now
                    }, video);
                }

                return true;
            }
            catch { }
            return false;
        }

        private static void HandleDashAudio(DashInfo info, IAppService app, Message message)
        {
            try
            {
                var size = info.Length;
                Log.Debug("Itag: " + info.ITag + " " + info.ITag);
                var name = Helpers.GetFileName(new Uri(info.Url));
                var ext = Path.GetExtension(name);

                if (string.IsNullOrEmpty(ext))
                {
                    ext = info.Mime.Contains("webm") ? ".webm" : info.Mime.Contains("mp4") ? ".mp4" : "mkv";
                }

                var quality = ext.Substring(1)?.ToUpperInvariant();
                var displayText = $"[{quality} AUDIO] {(size > 0 ? Helpers.FormatSize(size) : string.Empty)}";

                var video = new SingleSourceHTTPDownloadInfo
                {
                    Uri = info.Url,
                    Headers = info.Headers,
                    File = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url))) + ext,
                    Cookies = info.Cookies,
                    ContentLength = size,
                    ContentType = info.Mime
                };

                app.AddVideoNotification(new StreamingVideoDisplayInfo
                {
                    Quality = displayText,
                    Size = size,
                    CreationTime = DateTime.Now
                }, video);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private static DualSourceHTTPDownloadInfo CreateDualSourceHTTPDownloadInfo(DashInfo info1, DashInfo info2, Message message)
        {
            var (video, audio) = info1.IsVideo ? (info1, info2) : (info2, info1);
            return new DualSourceHTTPDownloadInfo
            {
                Uri1 = video.Url,
                Uri2 = audio.Url,
                Headers1 = video.Headers,
                Headers2 = audio.Headers,
                File = Helpers.SanitizeFileName(message.File ?? Helpers.GetFileName(new Uri(message.Url))) + ".mkv",
                Cookies1 = video.Cookies,
                Cookies2 = audio.Cookies,
                ContentLength = video.Length + audio.Length
            };
        }

        internal static void ProcessNormalVideo(Message message2, IAppService app)
        {
            if (IsMediaFragment(message2.GetRequestHeaderFirstValue("Referer")))
            {
                Log.Debug($"Skipping url:{message2.Url} as it seems a media fragment");
                return;
            }

            var file = (message2.File ?? Helpers.GetFileName(new Uri(message2.Url)));
            var type = message2.GetResponseHeaderFirstValue("Content-Type")?.ToLowerInvariant() ?? string.Empty;
            var len = message2.GetContentLength();
            if (string.IsNullOrEmpty(file))
            {
                file = Helpers.GetFileName(new Uri(message2.Url));
            }
            string ext;
            if (type.Contains("video/mp4"))
            {
                ext = "mp4";
            }
            else if (type.Contains("video/x-flv"))
            {
                ext = "flv";
            }
            else if (type.Contains("video/webm"))
            {
                ext = "mkv";
            }
            else if (type.Contains("matroska") || type.Contains("mkv"))
            {
                ext = "mkv";
            }
            else if (type.Equals("audio/mpeg") || type.Contains("audio/mp3"))
            {
                ext = "mp3";
            }
            else if (type.Contains("audio/aac"))
            {
                ext = "aac";
            }
            else if (type.Contains("audio/mp4"))
            {
                ext = "m4a";
            }
            else
            {
                return;
            }

            var video = new SingleSourceHTTPDownloadInfo
            {
                Uri = message2.Url,
                Headers = message2.RequestHeaders,
                File = Helpers.SanitizeFileName(file) + "." + ext,
                Cookies = message2.Cookies,
                ContentLength = len
            };

            var size = long.Parse(message2.GetResponseHeaderFirstValue("Content-Length"));
            var displayText = $"[{ext.ToUpperInvariant()}] {(size > 0 ? Helpers.FormatSize(size) : string.Empty)}";
            app.AddVideoNotification(new StreamingVideoDisplayInfo
            {
                Quality = displayText,
                Size = size,
                CreationTime = DateTime.Now
            }, video); ;
        }

        public static bool IsNormalVideo(int itag)
        {
            return ((itag > 4 && itag < 79) || (itag > 81 && itag < 86) || (itag > 99 && itag < 103));
        }

        private static bool AddIfNew(DashInfo info, List<DashInfo> list)
        {
            for (int i = list.Count - 1; i >= 0; i--)
            {
                var di = list[i];
                if (di.Length == info.Length && di.ID == info.ID)
                {
                    return false;
                }
            }
            list.Add(info);
            return true;
        }

        internal static bool IsHLS(string contentType)
        {
            foreach (var key in new string[] { "mpegurl", ".m3u8", "m3u8" })
            {
                if (contentType.Contains(key))
                {
                    return true;
                }
            }
            return false;
        }

        internal static bool IsDASH(string contentType)
        {
            foreach (var key in new string[] { "dash" })
            {
                if (contentType.Contains(key))
                {
                    return true;
                }
            }
            return false;
        }

        internal static bool IsYtFormat(string contentType)
        {
            foreach (var key in new string[] { "application/json" })
            {
                if (contentType.ToLowerInvariant().Contains(key))
                {
                    return true;
                }
            }
            return false;
        }

        public static bool AddToQueue(DashInfo info)
        {
            lock (lockObject)
            {
                if (videoQueue.Count > 32)
                {
                    videoQueue.RemoveAt(0);
                }
                if (audioQueue.Count > 32)
                {
                    audioQueue.RemoveAt(0);
                }
                if (info.IsVideo)
                {
                    return AddIfNew(info, videoQueue);
                }
                else
                {
                    return AddIfNew(info, audioQueue);
                }
            }
        }

        public static DashInfo GetDASHPair(DashInfo info)
        {
            lock (lockObject)
            {
                if (info.IsVideo)
                {
                    if (audioQueue.Count < 1)
                        return null;
                    for (int i = audioQueue.Count - 1; i >= 0; i--)
                    {
                        var di = audioQueue[i];
                        if (di.ID == info.ID)
                        {
                            return di;
                        }
                    }
                }
                else
                {
                    if (videoQueue.Count < 1)
                        return null;
                    for (int i = videoQueue.Count - 1; i >= 0; i--)
                    {
                        var di = videoQueue[i];
                        if (di.ID == info.ID)
                        {
                            if (lastVid?.Length == di.Length)
                            {
                                return null;
                            }
                            lastVid = di;
                            return di;
                        }
                    }
                }
                return null;
            }
        }

        internal static string? DownloadManifest(Message message)
        {
            try
            {
                using var http = HttpClientFactory.NewHttpClient(null);
                http.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);

                var acceptHeaderAdded = false;
                var headers = new Dictionary<string, List<string>>();
                var cookies = new Dictionary<string, string>();

                foreach (var header in message.RequestHeaders)
                {
                    headers.Add(header.Key, header.Value);
                    if (header.Key.ToLowerInvariant() == "accept")
                    {
                        acceptHeaderAdded = true;
                    }
                }

                if (acceptHeaderAdded)
                {
                    headers.Add("Accept", new List<string> { "*/*" });
                }

                foreach (var cookie in message.Cookies)
                {
                    cookies.Add(cookie.Key, cookie.Value);
                }

                byte[]? body = null;
                if (message.RequestBody != null)
                {
                    body = Convert.FromBase64String(message.RequestBody);
                }

                var request = "POST" == message.RequestMethod ?
                    http.CreatePostRequest(new Uri(message.Url), headers, cookies, null, body) :
                    http.CreateGetRequest(new Uri(message.Url), headers, cookies, null);

                using var response = http.Send(request);
                Log.Debug("Downloading manifest response: " + response.StatusCode);
                var path = Path.GetTempFileName();

                using var fs = new FileStream(path, FileMode.Create);
                response.GetResponseStream().CopyTo(fs);
                Log.Debug("Downloaded manifest: " + message.Url + " -> " + path);
                return path;
            }
            catch (Exception e) { Log.Debug(e, "Error"); }

            return null;
        }

        private static bool IsMediaFragment(string referer)
        {
            if (string.IsNullOrEmpty(referer)) return false;
            var sha1 = ComputeHash(referer);
            lock (referersToSkip)
            {
                if (referersToSkip.ContainsKey(sha1))
                {
                    referersToSkip[sha1] = DateTime.Now;
                    return true;
                }
            }
            return false;
        }

        private static void AddToSkippedRefererList(string referer)
        {
            if (string.IsNullOrEmpty(referer)) return;
            lock (referersToSkip)
            {
                referersToSkip[ComputeHash(referer)] = DateTime.Now;
            }
        }

        private static string ComputeHash(string input)
        {
            using var sha1 = new SHA1Managed();
            var hash = sha1.ComputeHash(Encoding.UTF8.GetBytes(input));
            return string.Concat(hash.Select(b => b.ToString("x2")));
        }

        public static readonly Dictionary<Int32, string> Itags = new()
        {
            [5] = "240p",
            [133] = "240p",
            [6] = "270p",
            [134] = "360p",
            [135] = "480p",
            [136] = "720p",
            [264] = "1440p",
            [137] = "1080p",
            [266] = "2160p",
            [139] = "Low bitrate",
            [140] = "Med bitrate",
            [13] = "Small",
            [141] = "Hi  bitrate",
            [271] = "1440p",
            [272] = "2160p",
            [17] = "144p",
            [18] = "360p",
            [22] = "720p",
            [278] = "144p",
            [160] = "144p",
            [34] = "360p",
            [35] = "480p",
            [36] = "240p",
            [37] = "1080p",
            [38] = "1080p",
            [167] = "360p",
            [168] = "480p",
            [169] = "720p",
            [170] = "1080p",
            [298] = "720p",
            [43] = "360p",
            [171] = "Med bitrate",
            [299] = "2160p",
            [44] = "480p",
            [172] = "Hi  bitrate",
            [45] = "720p",
            [46] = "1080p",
            [302] = "720p",
            [303] = "1080p",
            [308] = "1440p",
            [313] = "2160p",
            [59] = "480p",
            [315] = "2160p",
            [78] = "480p",
            [82] = "360p 3D",
            [83] = "480p 3D",
            [84] = "720p 3D",
            [85] = "1080p 3D",
            [218] = "480p",
            [219] = "480p",
            [100] = "360p 3D",
            [101] = "480p 3D",
            [102] = "720p 3D",
            [242] = "240p",
            [243] = "360p",
            [244] = "480p",
            [245] = "480p",
            [246] = "480p",
            [247] = "720p",
            [248] = "1080p"
        };
    }

    class DashInfo
    {
        public string Url;
        public long Length;
        public bool IsVideo;
        public string ID;
        public int ITag;
        public string Mime;
        public Dictionary<string, List<string>> Headers;
        public Dictionary<string, string> Cookies;
    }
}
