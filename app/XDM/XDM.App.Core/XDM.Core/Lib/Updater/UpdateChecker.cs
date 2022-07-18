using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Clients.Http;

namespace XDM.Core.Lib.Common
{
    public class UpdateChecker
    {
        private static readonly string UserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.159 Safari/537.36";

        public static bool GetAppUpdates(
            Version appVersion,
            out IList<UpdateInfo> updates,
            out bool firstUpdate,
            UpdateMode updateMode = UpdateMode.All)
        {
            updates = new List<UpdateInfo>();

            var lastYoutubeDLUpdate = DateTime.MinValue;
            var lastFFmpegUpdate = DateTime.MinValue;
            firstUpdate = true;

            var updateHistoryFile = Path.Combine(Config.DataDir, "update-info.json");
            try
            {
                if (File.Exists(updateHistoryFile))
                {
                    var hist = JsonConvert.DeserializeObject<UpdateHistory>(File.ReadAllText(updateHistoryFile));
                    lastFFmpegUpdate = hist.FFmpegUpdateDate;
                    lastYoutubeDLUpdate = hist.YoutubeDLUpdateDate;
                    firstUpdate = false;
                }

                using var hc = HttpClientFactory.NewHttpClient(null);
                hc.Timeout = TimeSpan.FromSeconds(Config.Instance.NetworkTimeout);

                if ((updateMode & UpdateMode.AppUpdateOnly) == UpdateMode.AppUpdateOnly)
                {
                    var appUpdate = FindNewAppVersion(hc, appVersion);
                    if (appUpdate != null)
                    {
                        var au = appUpdate.Value;
                        au.IsExternal = false;
                        updates.Add(au);
                    }
                }

                if ((updateMode & UpdateMode.YoutubeDLUpdateOnly) == UpdateMode.YoutubeDLUpdateOnly)
                {
                    var youtubeDLUpdate = FindNewYoutubeDLVersion(hc, lastYoutubeDLUpdate);
                    if (youtubeDLUpdate != null)
                    {
                        updates.Add(youtubeDLUpdate.Value);
                    }
                }

                if ((updateMode & UpdateMode.FFmpegUpdateOnly) == UpdateMode.FFmpegUpdateOnly)
                {
                    var ffmpegUpdate = FindNewFFmpegVersion(hc, lastFFmpegUpdate);
                    if (ffmpegUpdate != null)
                    {
                        updates.Add(ffmpegUpdate.Value);
                    }
                }

                return updates.Count > 0;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "GetAppUpdates");
            }
            return false;
        }

        private static UpdateInfo? FindNewRelease(IHttpClient hc,
            string url,
            string? assetName,
            Predicate<GitHubRelease> condition)
        {
            try
            {
                var request = hc.CreateGetRequest(new Uri(url), new Dictionary<string, List<string>>
                {
                    ["User-Agent"] = new List<string> { UserAgent }
                });
                using var response = hc.Send(request);
                using var stream = response.GetResponseStream();
                using var streamReader = new StreamReader(stream);
                using var r = new JsonTextReader(streamReader);
                var serializer = new JsonSerializer();
                var release = serializer.Deserialize<GitHubRelease?>(r);
                if (!release.HasValue) return null;
                if (condition.Invoke(release.Value))
                {
                    if (release.Value.Assets == null) return null;
                    foreach (var asset in release.Value.Assets)
                    {
                        if (asset.Name == assetName || assetName == null)
                        {
                            return new UpdateInfo
                            {
                                Url = asset.Url,
                                Name = asset.Name,
                                Size = asset.Size,
                                TagName = release.Value.TagName,
                                IsExternal = true
                            };
                        }
                    }
                    return null;
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error in FindNewRelease");
            }
            return null;
        }

        private static Version ParseGitHubTag(string tag)
        {
            try
            {
                return new Version(tag);
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "ParseGitHubTag");
                return new Version(Int32.MaxValue, 0, 0);
            }
        }

        //TODO: Handle MacOS
        private static string GetYoutubeDLExecutableNameForCurrentOS() =>
            Environment.OSVersion.Platform == PlatformID.Win32NT ? "yt-dlp_x86.exe" : "yt-dlp";

        //TODO: Handle MacOS
        private static string GetFFmpegExecutableNameForCurrentOS() =>
            Environment.OSVersion.Platform == PlatformID.Win32NT ? "ffmpeg-x86.exe" : "ffmpeg";

        //TODO: Handle Linux and Mac
        private static string GetAppExecutableNameForCurrentOS() =>
            Environment.OSVersion.Platform == PlatformID.Win32NT ? "xdmsetup.exe" : "xdmsetup";

        private static UpdateInfo? FindNewYoutubeDLVersion(IHttpClient hc, DateTime lastUpdated) =>
            FindNewRelease(hc, "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest",
                GetYoutubeDLExecutableNameForCurrentOS(), r => r.PublishedAt > lastUpdated);

        private static UpdateInfo? FindNewFFmpegVersion(IHttpClient hc, DateTime lastUpdated) =>
            FindNewRelease(hc, "https://api.github.com/repos/subhra74/xdm-ffmpeg-update/releases/latest",
                GetFFmpegExecutableNameForCurrentOS(), r => r.PublishedAt > lastUpdated);

        private static UpdateInfo? FindNewAppVersion(IHttpClient hc, Version appVersion) =>
            FindNewRelease(hc, "https://api.github.com/repos/subhra74/xdm/releases/latest",
                null, r => ParseGitHubTag(r.TagName) > appVersion);
    }

    internal struct GitHubRelease
    {
        [JsonProperty("tag_name")]
        public string TagName { get; set; }
        public bool Draft { get; set; }
        public bool Prerelease { get; set; }
        [JsonProperty("published_at")]
        public DateTime PublishedAt { get; set; }
        public Assets[] Assets { get; set; }
        public string Body { get; set; }
    }

    internal struct Assets
    {
        public string Name { get; set; }
        [JsonProperty("browser_download_url")]
        public string Url { get; set; }
        public long Size { get; set; }
    }

    public struct UpdateInfo
    {
        public string Name { get; set; }
        public string Url { get; set; }
        public long Size { get; set; }
        public string TagName { get; set; }
        public bool IsExternal { get; set; }
    }

    public struct UpdateHistory
    {
        public DateTime YoutubeDLUpdateDate { get; set; }
        public DateTime FFmpegUpdateDate { get; set; }
    }

    [Flags]
    public enum UpdateMode
    {
        AppUpdateOnly = 0,
        FFmpegUpdateOnly = 1,
        YoutubeDLUpdateOnly = 2,
        All = AppUpdateOnly | FFmpegUpdateOnly | YoutubeDLUpdateOnly
    }
}

