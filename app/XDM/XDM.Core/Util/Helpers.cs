using Microsoft.Win32;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using TraceLog;
using XDM.Core;
using XDM.Core.UI;
using System.Text;
using Translations;
using XDM.Core.Downloader.Adaptive.Dash;
using XDM.Core.Downloader.Progressive.DualHttp;
using XDM.Core.Downloader.Adaptive.Hls;
using XDM.Core.Downloader.Progressive.SingleHttp;
using XDM.Core.Downloader;
using XDM.Messaging;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core.Util
{
    public static class Helpers
    {

        public static bool GetFreeSpace(string path, out long free)
        {
            free = 0;
            var rootPath = Path.GetPathRoot(path);
            if (!string.IsNullOrEmpty(rootPath))
            {
                var driveInfo = new DriveInfo(rootPath);
                free = driveInfo.AvailableFreeSpace;
                return true;
            }
            return false;
        }

        public static bool IsUriValid(string? url)
        {
            try
            {
                var u = new Uri(url);
                var scheme = u.Scheme?.ToLowerInvariant();
                if (scheme != "http" && scheme != "https")
                {
                    return false;
                }
                return true;
            }
            catch
            {
                return false;
            }
        }

        //public static bool ValidateError(string url)
        //{
        //    if (!string.IsNullOrEmpty(url))
        //    {
        //        return false;
        //    }
        //    try
        //    {
        //        new Uri(url);
        //        return true;
        //    }
        //    catch
        //    {
        //        return false;
        //    }
        //}

        public static void RunGC()
        {
#if NET35
            GC.Collect();
#elif NET5_0 || NET472
            GCSettings.LargeObjectHeapCompactionMode = GCLargeObjectHeapCompactionMode.CompactOnce;
            GCSettings.LatencyMode = GCLatencyMode.Batch;
            GC.Collect(GC.MaxGeneration, GCCollectionMode.Forced, true, true);
#elif NET45
            GCSettings.LatencyMode = GCLatencyMode.Batch;
            GC.Collect(GC.MaxGeneration, GCCollectionMode.Forced, true);
#endif
            //ReduceMemory();
        }

        static bool _toggle = true;

        internal static void ReduceMemory()
        {
            try
            {
                Process loProcess = Process.GetCurrentProcess();
                if (_toggle)
                {
                    loProcess.MaxWorkingSet = (IntPtr)((int)loProcess.MaxWorkingSet - 1);
                    loProcess.MinWorkingSet = (IntPtr)((int)loProcess.MinWorkingSet - 1);
                }
                else
                {
                    loProcess.MaxWorkingSet = (IntPtr)((int)loProcess.MaxWorkingSet + 1);
                    loProcess.MinWorkingSet = (IntPtr)((int)loProcess.MinWorkingSet + 1);
                }
                _toggle = !_toggle;
            }
            catch (Exception x)
            {
                Debug.WriteLine(x);
            }
        }

        public static bool IsBlockedHost(string url)
        {
            var host = new Uri(url).Host;
            foreach (var blockedHost in Config.Instance.BlockedHosts)
            {
                if (host.Contains(blockedHost))
                {
                    return true;
                }
            }
            return false;
        }

        public static bool IsCompressedJSorCSS(string url)
        {
            var file = FileHelper.GetFileName(new Uri(url));
            var ext = Path.GetExtension(file)?.ToLowerInvariant();
            if (ext == ".js.gz" || ext == ".css.gz" || ext == ".js.zip"
                || ext == ".css.zip" || ext == ".js.bz2" || ext == ".css.bz2")
                return true;
            return false;
        }

        public static AuthenticationInfo? GetAuthenticationInfoFromConfig(Uri url)
        {
            var host = url.Host;
            foreach (var item in Config.Instance.UserCredentials)
            {
                if (host.Contains(item.Host))
                {
                    return new AuthenticationInfo { UserName = item.User, Password = item.Password };
                }
            }
            return null;
        }

        //public static void SaveDownloadInfo<T>(string id, T downloadInfo)
        //{
        //    File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        //}

        public static void WriteStringSafe(string? text, BinaryWriter w)
        {
            w.Write(text ?? string.Empty);
        }

        public static long TickCount()
        {
#if NET5_0_OR_GREATER
            return Environment.TickCount64;
#else
            return (long)GetTickCount64();
#endif
        }

        public static IEnumerable<IInProgressDownloadRow> FilterByKeyword(
            IEnumerable<IInProgressDownloadRow> inProgressDownloads, string? searchKeyword)
        {
            return inProgressDownloads.Where(d => IsMatchesKeyword(d.Name, searchKeyword));
        }

        public static bool IsMatchesKeyword(string name, string? searchKeyword)
        {
            return string.IsNullOrEmpty(searchKeyword) ||
                name.ToLowerInvariant().Contains(searchKeyword?.ToLowerInvariant());
        }

        public static IEnumerable<FinishedDownloadItem> FilterByCategoryOrKeyword(
            IEnumerable<FinishedDownloadItem> finishedDownloads, string? searchKeyword, Category? category)
        {
            return finishedDownloads.Where(d => IsOfCategoryOrMatchesKeyword(d.Name, searchKeyword, category));
        }

        public static bool IsOfCategoryOrMatchesKeyword(string name, string? searchKeyword, Category? category)
        {
            var searchMatched = string.IsNullOrEmpty(searchKeyword) ||
                name.ToLowerInvariant().Contains(searchKeyword?.ToLowerInvariant());
            if (!searchMatched)
            {
                return false;
            }
            return IsOfCategory(name, category);
        }

        public static bool IsOfCategory(string name, Category? category)
        {
            if (category == null) return true;
            var ext = Path.GetExtension(name);
            return category.Value.FileExtensions.Contains(ext.ToUpperInvariant());
        }

        private static string GetStatusText(DownloadStatus status)
        {
            switch (status)
            {
                case DownloadStatus.Downloading:
                    return TextResource.GetText("STAT_DOWNLOADING");
                case DownloadStatus.Stopped:
                    return TextResource.GetText("STAT_STOPPED");
                case DownloadStatus.Finished:
                    return TextResource.GetText("STAT_FINISHED");
                case DownloadStatus.Waiting:
                    return TextResource.GetText("STAT_WAITING");
                default:
                    return status.ToString();
            }
        }

        public static string GenerateStatusText(InProgressDownloadItem ent)
        {
            var text = string.Empty;

            if (ent.Status == DownloadStatus.Downloading)
            {
                if (string.IsNullOrEmpty(ent.ETA) && string.IsNullOrEmpty(ent.DownloadSpeed))
                {
                    text = GetStatusText(ent.Status);
                }
                else if (string.IsNullOrEmpty(ent.ETA))
                {
                    text = ent.DownloadSpeed ?? string.Empty;
                }
                else if (string.IsNullOrEmpty(ent.DownloadSpeed))
                {
                    text = ent.ETA ?? string.Empty;
                }
                else
                {
                    text = $"{ent.DownloadSpeed} - {ent.ETA}";
                }
            }
            else
            {
                text = GetStatusText(ent.Status);
            }

            return text;
        }

        //public static string GetRunningFrameworkVersion()
        //{
        //    var netVer = Environment.Version;
        //    Assembly assObj = new object().GetType().Assembly;
        //    if (assObj != null)
        //    {
        //        AssemblyFileVersionAttribute attr;
        //        attr = (AssemblyFileVersionAttribute)assObj..GetCustomAttribute(typeof(AssemblyFileVersionAttribute));
        //        if (attr != null)
        //        {
        //            netVer = attr.Version;
        //        }
        //    }
        //    return netVer;
        //}

        [DllImport("kernel32.dll")]
        public static extern UInt64 GetTickCount64();

        public static string FindYDLBinary()
        {
            //var executableName = Environment.OSVersion.Platform == PlatformID.Win32NT ? "youtube-dl.exe" : "youtube-dl";
            var executableName = Environment.OSVersion.Platform == PlatformID.Win32NT ? "yt-dlp_x86.exe" : "yt-dlp_linux";
            var path = Path.Combine(Config.DataDir, executableName);
            if (File.Exists(path))
            {
                return path;
            }
            path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, executableName);
            if (File.Exists(path))
            {
                return path;
            }
            var ydlPathEnvVar = Environment.GetEnvironmentVariable("YOUTUBEDL_HOME");
            if (ydlPathEnvVar != null)
            {
                return Path.Combine(ydlPathEnvVar, executableName);
            }
            var ydlExec = PlatformHelper.FindExecutableFromSystemPath(executableName);
            if (ydlExec != null) return ydlExec;
            throw new FileNotFoundException("YoutubeDL executable not found");
        }

        public static string MakeCookieString(Dictionary<string, string> cookies)
        {
            var cookieBuf = new StringBuilder();
            var n = cookies.Count;
            var c = 0;
            foreach (var key in cookies.Keys)
            {
                cookieBuf.Append(key).Append("=").Append(cookies[key]);
                if (c < n - 1)
                {
                    cookieBuf.Append("; ");
                }
                c++;
            }
            return cookieBuf.ToString();
        }

        public static string MakeCookieString(IEnumerable<string> cookies)
        {
            var cookieBuf = new StringBuilder();
            var first = true;
            foreach (var key in cookies)
            {
                if (!first)
                {
                    cookieBuf.Append("; ");
                }
                cookieBuf.Append(key);
                first = false;
            }
            return cookieBuf.ToString();
        }

        public static void UpdateRecentFolderList(string folder)
        {
            if (string.IsNullOrEmpty(folder))
            {
                return;
            }
            if (!Config.Instance.RecentFolders.Contains(folder))
            {
                Config.Instance.RecentFolders.Insert(0, folder);
            }
            Config.Instance.FolderSelectionMode = FolderSelectionMode.Manual;
            Config.SaveConfig();
        }

        public static string? GetManualDownloadFolder()
        {
            if (Config.Instance.FolderSelectionMode == FolderSelectionMode.Manual)
            {
                if (Config.Instance.RecentFolders != null && Config.Instance.RecentFolders.Count > 0)
                {
                    if (!string.IsNullOrEmpty(Config.Instance.UserSelectedDownloadFolder) &&
                        Config.Instance.RecentFolders.Contains(Config.Instance.UserSelectedDownloadFolder))
                    {
                        return Config.Instance.UserSelectedDownloadFolder;
                    }
                    return Config.Instance.RecentFolders[0];
                }
                return Config.Instance.DefaultDownloadFolder;
            }
            return null;
        }

        public static string GetVideoDownloadFolder()
        {
            var folder = GetManualDownloadFolder();
            if (string.IsNullOrEmpty(folder))
            {
                folder = FileHelper.GetDownloadFolderByFileName("video.mp4");
            }
            return folder!;
        }

        public static int GetSpeedLimit() => Config.Instance.EnableSpeedLimit ? Config.Instance.DefaltDownloadSpeed : 0;

        public static string EncodeToCharCode(string text)
        {
            var sb = new StringBuilder();
            int count = 0;
            foreach (char ch in text)
            {
                if (count > 0)
                    sb.Append(",");
                sb.Append((int)ch);
                count++;
            }
            return sb.ToString();
        }
    }
}
