using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text.RegularExpressions;
using TraceLog;
using XDM.Core;
using XDM.Core.Clients.Http;

namespace XDM.Core.Updater
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

            var lastYoutubeDLUpdate = DateTime.MaxValue;

            if (updateMode != UpdateMode.All)
            {
                //this is being called for missing ytdlp/ffmpeg components, not the update which run at app startup
                lastYoutubeDLUpdate = DateTime.MinValue;
            }

            firstUpdate = true;

            var updateHistoryFile = Path.Combine(Config.AppDir, "ytdlp-update.json");
            try
            {
                if (File.Exists(updateHistoryFile))
                {
                    var hist = JsonConvert.DeserializeObject<UpdateHistory>(File.ReadAllText(updateHistoryFile));
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

                //if ((updateMode & UpdateMode.FFmpegUpdateOnly) == UpdateMode.FFmpegUpdateOnly)
                //{
                //    var ffmpegUpdate = FindNewFFmpegVersion(hc, lastFFmpegUpdate);
                //    if (ffmpegUpdate != null)
                //    {
                //        updates.Add(ffmpegUpdate.Value);
                //    }
                //}

                return true;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "GetAppUpdates");
            }
            return false;
        }

        private static UpdateInfo? FindNewRelease(IHttpClient hc,
            string url,
            Predicate<GitHubRelease> condition,
            AssetPattern? assetPattern)
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
                        if (assetPattern != null && asset.Name.StartsWith(assetPattern.Value.Prefix))
                        {
                            var found = false;
                            if (assetPattern.Value.Extensions == null || assetPattern.Value.Extensions.Length == 0)
                            {
                                found = true;
                            }
                            else
                            {
                                foreach (var ext in assetPattern.Value.Extensions!)
                                {
                                    if (string.IsNullOrEmpty(ext) || asset.Name.EndsWith(ext)) { found = true; break; }
                                }
                            }
                            if (found)
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
        private static AssetPattern GetYoutubeDLExecutableNameForCurrentOS() =>
            Environment.OSVersion.Platform == PlatformID.Win32NT ?
            new AssetPattern
            {
                Prefix = "yt-dlp_x86",
                Extensions = new string[] { ".exe" }
            } : new AssetPattern
            {
                Prefix = "yt-dlp",
                Extensions = new string[] { }
            };

        ////TODO: Handle MacOS
        //private static AssetPattern GetFFmpegExecutableNameForCurrentOS() =>
        //    Environment.OSVersion.Platform == PlatformID.Win32NT ? new AssetPattern
        //    {
        //        Prefix = "ffmpeg-x86",
        //        Extensions = new string[] { ".exe" }
        //    } : new AssetPattern
        //    {
        //        Prefix = "ffmpeg",
        //        Extensions = new string[] { }
        //    };

        //TODO: Handle Linux and Mac
        private static AssetPattern? GetAppInstallerNameForCurrentOS()
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return new AssetPattern { Prefix = "xdmsetup", Extensions = new string[] { ".msi", ".exe" } };
            }
            var baseDir = AppDomain.CurrentDomain.BaseDirectory;
            var pkgNameFile = Path.Combine(baseDir, "source_pkg");
            if (File.Exists(pkgNameFile))
            {
                var pkgNamePatterns = File.ReadAllText(pkgNameFile);
                var arr = pkgNamePatterns.Split('|');
                if (arr.Length == 2)
                {
                    return new AssetPattern { Prefix = arr[0], Extensions = arr[1].Split(';') };
                }
            }
            return null;
        }

        private static UpdateInfo? FindNewYoutubeDLVersion(IHttpClient hc, DateTime lastUpdated) =>
            FindNewRelease(hc, Links.YtDlpReleaseGH, r => r.PublishedAt > lastUpdated,
                GetYoutubeDLExecutableNameForCurrentOS());

        //private static UpdateInfo? FindNewFFmpegVersion(IHttpClient hc, DateTime lastUpdated) =>
        //    FindNewRelease(hc, Links.FFmpegCustomReleaseGH, r => r.PublishedAt > lastUpdated,
        //        GetFFmpegExecutableNameForCurrentOS());

        private static UpdateInfo? FindNewAppVersion(IHttpClient hc, Version appVersion) =>
            FindNewRelease(hc, Links.AppLatestReleaseGH, r => ParseGitHubTag(r.TagName) > appVersion,
                GetAppInstallerNameForCurrentOS());
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
    }

    public struct AssetPattern
    {
        public string Prefix { get; set; }
        public string[] Extensions { get; set; }
    }

    [Flags]
    public enum UpdateMode
    {
        AppUpdateOnly = 4,
        //FFmpegUpdateOnly = 1,
        YoutubeDLUpdateOnly = 2,
        All = AppUpdateOnly /*| FFmpegUpdateOnly*/ | YoutubeDLUpdateOnly
    }
}

