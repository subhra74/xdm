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
using XDM.Core.Lib.Common;
using XDM.Core.Lib.UI;
using System.Text;
using Translations;
using XDM.Core.Lib.Downloader.Adaptive.Dash;
using XDM.Core.Lib.Downloader.Progressive.DualHttp;
using XDM.Core.Lib.Downloader.Adaptive.Hls;
using XDM.Core.Lib.Downloader.Progressive.SingleHttp;
using XDM.Core.Lib.Downloader;

#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

namespace XDM.Core.Lib.Util
{
    public static class Helpers
    {
        private const int GB = 1024 * 1024 * 1024, MB = 1024 * 1024, KB = 1024;

        public static readonly Dictionary<string, string?> MimeTypes;

        public static readonly Regex RxDuration = new Regex(@"Duration:\s+(\d\d):(\d\d):(\d\d)\.\d\d,\s", RegexOptions.Compiled);
        public static readonly Regex RxTime = new Regex(@"frame=.*?time=(\d\d):(\d\d):(\d\d)\.\d\d.*?bitrate=", RegexOptions.Compiled);
        public static readonly Regex RxFileWithinQuote = new Regex("\\\"(.*)\\\"");

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

        public static string ToHMS(long sec)
        {
            long hrs = 0, min = 0;
            hrs = sec / 3600;
            min = (sec % 3600) / 60;
            sec = sec % 60;
            String str = hrs.ToString().PadLeft(2, '0') + ":" + min.ToString().PadLeft(2, '0') + ":" + sec.ToString().PadLeft(2, '0');
            return str;
        }

        public static string FormatSize(double length)
        {
            if (length <= 0)
                return "---";
            if (length > GB)
            {
                return $"{length / GB:F1}G";
            }
            else if (length > MB)
            {
                return $"{length / MB:F1}M";
            }
            else if (length > KB)
            {
                return $"{length / KB:F1}K";
            }
            else
            {
                return $"{(long)length}B";
            }
        }

        public static bool IsUriValid(string url)
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

        public static string SanitizeFileName(string fileName)
        {
            if (fileName == null) return null;
            var file = fileName.Split('/').Last();
            return string.Join("_", file.Split(Path.GetInvalidFileNameChars()));
        }

        public static string GetOsDefaultDownloadFolder()
        {
            if (Environment.OSVersion.Platform == PlatformID.Win32NT)
            {
                return Win32NativeMethods.GetDownloadDirectoryPath();
            }
            else
            {
#if NET5_0_OR_GREATER
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
#else
                throw new PlatformNotSupportedException("Please use program which was compiled for dotnet5");
#endif
            }
        }

        public static string GetDownloadFolderByFileName(string file)
        {
            try
            {
                var ext = Path.GetExtension(file)?.ToUpperInvariant();
                foreach (var category in Config.Instance.Categories)
                {
                    if (ext != null && category.FileExtensions.Contains(ext))
                    {
                        return category.DefaultFolder;
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }
            return Config.Instance.DefaultDownloadFolder;
        }

        //public static string GetFileNameFromProbeResult(ProbeResult probeResult, bool keepOriginalName = false,
        //    string originalName = null)
        //{
        //    if (keepOriginalName && string.IsNullOrEmpty(originalName))
        //    {
        //        throw new InvalidOperationException("keepOriginalName is set with no original file name");
        //    }
        //    var fileName = "";
        //    if (probeResult.AttachmentName != null)
        //    {
        //        fileName = probeResult.AttachmentName;
        //    }
        //    else
        //    {
        //        if (keepOriginalName)
        //        {
        //            fileName = Helpers.AddFileExtension(originalName, probeResult.ContentType);
        //        }
        //        else
        //        {
        //            fileName = Helpers.GetFileName(
        //                probeResult.FinalUri, probeResult.ContentType);
        //        }
        //    }
        //    return fileName;
        //}

        public static bool AddFileExtension(string name, string contentType, out string nameWithExt)
        {
            name = SanitizeFileName(name);
            if (name.EndsWith("."))
            {
                name = name.TrimEnd('.');
            }
            if (string.IsNullOrEmpty(contentType))
            {
                nameWithExt = name;
                return false;
            }
            if (contentType == "text/html")
            {
                nameWithExt = name + ".html";
                return true;
            }
            else
            {
                try
                {
                    var ext = MimeTypes.GetValueOrDefault(contentType.ToLowerInvariant());
                    if (!string.IsNullOrEmpty(ext))
                    {
                        var prevExt = Path.GetExtension(name);
                        var nameWithoutExt = Path.GetFileNameWithoutExtension(name);
                        if (!("." + ext).Equals(prevExt, StringComparison.InvariantCultureIgnoreCase))
                        {
                            nameWithExt = nameWithoutExt + "." + ext;
                            return true;
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error in AddFileExtension");
                }

                nameWithExt = name;
                return true;
            }
        }

        public static string GetFileName(Uri uri, string contentType = null)
        {
            var name = Path.GetFileName(uri.LocalPath);
            if (string.IsNullOrEmpty(name))
            {
                name = uri.Host.Replace('.', '_');
            }
            name = SanitizeFileName(name);
            if (string.IsNullOrEmpty(contentType))
            {
                return name;
            }

            if (contentType == "text/html")
            {
                return Path.ChangeExtension(name, ".html");
            }
            else
            {
                if (!Path.HasExtension(name))
                {
                    var ext = MimeTypes.GetValueOrDefault(contentType.ToLowerInvariant(), null);
                    if (!string.IsNullOrEmpty(ext))
                    {
                        name += "." + ext;
                    }
                }
                return name;
            }
        }

        static Helpers()
        {
            var mimeBuilder = new Dictionary<string, string?>();
            mimeBuilder["application/x-msdownload"] = "dll";
            mimeBuilder["image/jpeg"] = "jpeg";
            mimeBuilder["image/bmp"] = "bmp";
            mimeBuilder["image/gif"] = "gif";
            mimeBuilder["image/x-icon"] = "ico";
            mimeBuilder["image/svg+xml"] = "svg";
            mimeBuilder["application/x-compressed"] = "tgz";
            mimeBuilder["application/x-shockwave-flash"] = "swf";
            mimeBuilder["video/x-msvideo"] = "avi";
            mimeBuilder["application/postscript"] = "ps";
            mimeBuilder["video/x-flv"] = "flv";
            mimeBuilder["audio/x-wav"] = "wav";
            mimeBuilder["application/vnd.ms-excel"] = "xls";
            mimeBuilder["audio/basic"] = "au";
            mimeBuilder["audio/x-aiff"] = "aiff";
            mimeBuilder["text/plain"] = "txt";
            mimeBuilder["application/x-gzip"] = "gz";
            mimeBuilder["application/msword"] = "doc";
            mimeBuilder["application/pdf"] = "pdf";
            mimeBuilder["application/x-compress"] = "z";
            mimeBuilder["application/x-javascript"] = "js";
            mimeBuilder["video/3gpp"] = "3gp";
            mimeBuilder["audio/mid"] = "mid";
            mimeBuilder["application/x-cpio"] = "cpio";
            mimeBuilder["application/vnd.ms-powerpoint"] = "ppt";
            mimeBuilder["audio/mpeg"] = "mp3";
            mimeBuilder["application/rtf"] = "rtf";
            mimeBuilder["application/x-tar"] = "tar";
            mimeBuilder["video/x-ms-wmv"] = "wmv";
            mimeBuilder["application/x-bcpio"] = "bcpio";
            mimeBuilder["text/html"] = "html";
            mimeBuilder["video/mpeg"] = "mpeg";
            mimeBuilder["image/tiff"] = "tiff";
            mimeBuilder["application/x-stuffit"] = "sit";
            mimeBuilder["application/zip"] = "zip";
            mimeBuilder["text/css"] = "css";
            mimeBuilder["application/x-gtar"] = "gtar";
            mimeBuilder["video/quicktime"] = "qt";
            mimeBuilder["video/flv"] = "flv";
            mimeBuilder["video/mp4"] = "mp4";
            mimeBuilder["video/mp2t"] = "ts";
            mimeBuilder["video/mp2t"] = "ts";
            mimeBuilder["video/x-matroska"] = "mkv";
            mimeBuilder["audio/mp4"] = "mp4";
            mimeBuilder["audio/mp2t"] = "ts";
            mimeBuilder["audio/x-matroska"] = "mkv";
            mimeBuilder["video/webm"] = "mkv";
            mimeBuilder["audio/webm"] = "mkv";

            MimeTypes = mimeBuilder;
        }

        public static (string Key, string Value, bool Success) ParseKeyValuePair(string line, char delimiter)
        {
            line = line.Trim();
            int index = line.IndexOf(delimiter);
            if (index < 1) return ("", "", false);
            string key = line.Substring(0, index).Trim();
            string val = line.Substring(index + 1).Trim();
            return (Key: key, Value: val, Success: true);
        }

        public static bool ValidateError(string url)
        {
            if (!string.IsNullOrEmpty(url))
            {
                return false;
            }
            try
            {
                new Uri(url);
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static long ParseTime(Match match)
        {
            if (match.Success && match.Groups.Count == 4)
            {
                var h = Convert.ToInt32(match.Groups[1].Value, 10) * 3600;
                var m = Convert.ToInt32(match.Groups[2].Value, 10) * 60;
                var s = Convert.ToInt32(match.Groups[3].Value, 10);
                return h + m + s;
            }
            return -1;
        }

        public static string? FindExecutableFromSystemPath(string executableName)
        {
            var values = Environment.GetEnvironmentVariable("PATH");
            foreach (var spath in values?.Split(Path.PathSeparator) ?? new string[0])
            {
                var fullPath = Path.Combine(spath, executableName);
                if (File.Exists(fullPath))
                    return fullPath;
            }
            return null;
        }

        public static bool OpenFile(string path)
        {
            if (!File.Exists(path))
            {
                return false;
            }
            try
            {
                var os = Environment.OSVersion.Platform;
                switch (os)
                {
                    case PlatformID.Win32NT:
                        var psiShellEx = new ProcessStartInfo
                        {
                            FileName = path,
                            UseShellExecute = true
                        };
                        Process.Start(psiShellEx);
                        return true;
#if NET5_0_OR_GREATER
                    case PlatformID.Unix:
                        var psi = new ProcessStartInfo
                        {
                            FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "xdg-open" : "open"
                        };
                        psi.Arguments = "\"" + path + "\"";
                        Process.Start(psi);
                        return true;
#endif
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "OpenFile");
            }
            return false;
        }

        public static bool OpenFolder(string path, string? file = null)
        {
            if (file != null)
            {
                if (Directory.Exists(path))
                {
                    try
                    {
                        var os = Environment.OSVersion.Platform;
                        switch (os)
                        {
                            case PlatformID.Win32NT:
                                var psiShellEx = new ProcessStartInfo
                                {
                                    FileName = "explorer",
                                };
                                psiShellEx.Arguments = $"/select, \"{Path.Combine(path, file)}\"";
                                Process.Start(psiShellEx);
                                return true;
#if NET5_0_OR_GREATER
                            case PlatformID.Unix:
                                var psi = new ProcessStartInfo
                                {
                                    FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "xdg-open" : "open"
                                };
                                psi.Arguments = $"\"{path}\"";
                                Process.Start(psi);
                                return true;
#endif
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, "OpenFolder");
                    }
                }
                return false;
            }
            return OpenFile(path);
        }

        public static void OpenBrowser(string url)
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var psiShellEx = new ProcessStartInfo
                    {
                        FileName = url,
                    };
                    psiShellEx.UseShellExecute = true;
                    Process.Start(psiShellEx);
                    break;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    var psi = new ProcessStartInfo
                    {
                        FileName = RuntimeInformation.IsOSPlatform(OSPlatform.OSX) ? "xdg-open" : "open"
                    };
                    psi.Arguments = "\"" + url + "\"";
                    Process.Start(psi);
                    break;
#endif
            }
        }

        public static string GetUniqueFileName(string file, string folder)
        {
            var path = Path.Combine(folder, file);
            var name = Path.GetFileNameWithoutExtension(file);
            var ext = Path.GetExtension(file);
            var count = 0;
            while (File.Exists(path))
            {
                count++;
                path = Path.Combine(folder, name + "_" + count + ext);
            }
            return count == 0 ? file : name + "_" + count + ext;
        }

        public static string GuessContainerFormatFromSegmentExtension(string ext)
        {
            ext = ext?.ToLowerInvariant() ?? string.Empty;
            if (ext == ".ts")
            {
                return ext;
            }
            foreach (var mp4Ext in new string[] { ".m4", ".mp4", ".fmp4" })
            {
                if (ext.StartsWith(mp4Ext)) return ".mp4";
            }
            return ".mkv";
        }

        public static string GuessContainerFormatFromSegmentExtension(string ext1, string ext2)
        {
            var ex1 = GuessContainerFormatFromSegmentExtension(ext1);
            var ex2 = GuessContainerFormatFromSegmentExtension(ext2);
            if (ex1 == ex2)
            {
                return ex1;
            }
            else
            {
                return ".mkv";
            }
        }

        public static string GetExtensionFromMimeType(string mimeType)
        {
            if (mimeType == null) return null;
            if (MimeTypes.ContainsKey(mimeType))
            {
                return "." + MimeTypes[mimeType];
            }
            return null;
        }

        //public static bool IsDiskFull(Exception ex)
        //{
        //    const int HR_ERROR_HANDLE_DISK_FULL = unchecked((int)0x80070027);
        //    const int HR_ERROR_DISK_FULL = unchecked((int)0x80070070);

        //    return ex.HResult == HR_ERROR_HANDLE_DISK_FULL
        //        || ex.HResult == HR_ERROR_DISK_FULL;
        //}

        private static string GetFileNameFromQuote(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return null;
            }
            var matcher = RxFileWithinQuote.Match(text);
            if (matcher.Success)
            {
                return matcher.Groups[1].Value;
            }
            return null;
        }

        private static string FindChromeExecutableFromRegistry()
        {
            try
            {
                using var regKey = Registry.ClassesRoot.OpenSubKey(@"ChromeHTML\shell\open\command");
                return GetFileNameFromQuote((string)regKey.GetValue(null));
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error fetching chrome location from registry");
            }
            return null;
        }

        public static string GetChromeExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var chromeExe = FindChromeExecutableFromRegistry();
                    if (!string.IsNullOrEmpty(chromeExe))
                    {
                        return chromeExe;
                    }
                    var suffix = "Google\\Chrome\\Application\\chrome.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    return null;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        const string macChromeExe = "/Applications/Google Chrome.app";
                        if (File.Exists(macChromeExe))
                        {
                            return macChromeExe;
                        }
                    }
                    else
                    {
                        const string linuxChromeExe = "/usr/bin/google-chrome";
                        if (File.Exists(linuxChromeExe))
                        {
                            return linuxChromeExe;
                        }
                    }
                    break;
#endif
            }

            return null;
        }

        public static string GetFireFoxExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var suffix = "Mozilla Firefox\\firefox.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    return null;
#if NET5_0_OR_GREATER
                case PlatformID.Unix:
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                    {
                        const string macFireFoxExe = "/Applications/Firefox.app";
                        if (File.Exists(macFireFoxExe))
                        {
                            return macFireFoxExe;
                        }
                    }
                    else
                    {
                        const string linuxFireFoxExe = "/usr/bin/firefox";
                        if (File.Exists(linuxFireFoxExe))
                        {
                            return linuxFireFoxExe;
                        }
                    }
                    break;
#endif
            }

            return null;
        }

        public static string GetEdgeExecutable()
        {
            var os = Environment.OSVersion.Platform;
            switch (os)
            {
                case PlatformID.Win32NT:
                    var suffix = @"Edge\Application\msedge.exe";
                    foreach (var path in new[] {
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("PROGRAMFILES(X86)",
                            EnvironmentVariableTarget.Machine),suffix),
                        Path.Combine(Environment.GetEnvironmentVariable("LOCALAPPDATA",
                            EnvironmentVariableTarget.User),suffix),
                    })
                    {
                        if (File.Exists(path)) return path;
                    }
                    break;
            }

            return null;
        }

        private static string CreateNativeMessagingHostManifest(NativeHostBrowser browser, string name)
        {
            if (browser == NativeHostBrowser.Firefox)
            {
                var json = JsonConvert.SerializeObject(new
                {
                    name = name,
                    description = "Native messaging host for Xtreme Download Manager",
                    path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "NativeMessagingHost.exe"),
                    type = "stdio",
                    allowed_extensions = new[] {
                        "browser-mon@xdman.sourceforge.net"
                    }
                });
                return json;
                //var manifestPath = Path.Combine(Config.DataDir, "xdmff.native_host.json");
                //File.WriteAllText(manifestPath, json);
                //return manifestPath;
            }
            else
            {
                var json = JsonConvert.SerializeObject(new
                {
                    name = name,
                    description = "Native messaging host for Xtreme Download Manager",
                    path = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "NativeMessagingHost.exe"),
                    type = "stdio",
                    allowed_origins = new[] {
                        "chrome-extension://danmljfachfhpbfikjgedlfifabhofcj/",
                        "chrome-extension://dkckaoghoiffdbomfbbodbbgmhjblecj/",
                        "chrome-extension://ejpbcmllmliidhlpkcgbphhmaodjihnc/",
                        "chrome-extension://fogpiboapmefmkbodpmfnohfflonbgig/"
                    }
                }, Formatting.Indented);
                return json;
                //var manifestPath = Path.Combine(Config.DataDir, "xdm_chrome.native_host.json");
                //File.WriteAllText(manifestPath, json);
                //return manifestPath;
            }
        }

        public static void InstallNativeMessagingHost(NativeHostBrowser browser)
        {
            var name = browser == NativeHostBrowser.Firefox ? "xdmff.native_host" :
                    "xdm_chrome.native_host";
            var manifestJSON = CreateNativeMessagingHostManifest(browser, name);

            var os = Environment.OSVersion.Platform;
            if (os == PlatformID.Win32NT)
            {
                var manifestPath = Path.Combine(Config.DataDir, $"{name}.json");
                File.WriteAllText(manifestPath, manifestJSON);
                var regPath = (browser == NativeHostBrowser.Firefox ?
                    @"Software\Mozilla\NativeMessagingHosts\" :
                    @"SOFTWARE\Google\Chrome\NativeMessagingHosts");
                using var regKey = Registry.CurrentUser.CreateSubKey(regPath);
                using var key = regKey.CreateSubKey(name, RegistryKeyPermissionCheck.ReadWriteSubTree);
                key.SetValue(null, manifestPath);
            }
            else
            {
#if NET5_0_OR_GREATER
                string manifestPath;
                if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                {
                    if (browser == NativeHostBrowser.Firefox)
                    {
                        manifestPath = $"~/Library/Application Support/Mozilla/NativeMessagingHosts/{name}.json";
                    }
                    else
                    {
                        manifestPath = $"~/Library/Application Support/Google/Chrome/NativeMessagingHosts/{name}.json";
                    }
                }
                else
                {
                    if (browser == NativeHostBrowser.Firefox)
                    {
                        manifestPath = $"~/.mozilla/native-messaging-hosts/{name}.json";
                    }
                    else
                    {
                        manifestPath = $"~/.config/google-chrome/NativeMessagingHosts/{name}.json";
                    }
                }

                File.WriteAllText(manifestPath, manifestJSON);
#endif
            }
        }

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

        public static bool EnableAutoStart(bool enable)
        {
            try
            {
                var os = Environment.OSVersion.Platform;
                if (os == PlatformID.Win32NT)
                {
                    using var hkcuRun = Registry.CurrentUser.CreateSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run");
                    if (hkcuRun != null)
                    {
                        if (enable)
                        {
                            var xdmExe = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app.exe");
                            hkcuRun.SetValue("XDM", $"\"{xdmExe}\" -m");
                        }
                        else
                        {
                            hkcuRun.DeleteValue("XDM", false);
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error setting registry key");
            }
            return false;
        }

        public static bool IsAutoStartEnabled()
        {
            try
            {
                var os = Environment.OSVersion.Platform;
                if (os == PlatformID.Win32NT)
                {
                    using var hkcuRun = Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Run");
                    if (hkcuRun != null)
                    {
                        var command = (string)hkcuRun.GetValue("XDM");
                        var path = GetFileNameFromQuote(command);
                        return !string.IsNullOrEmpty(path) &&
                            path == Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "xdm-app.exe");
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error setting registry key");
            }

            return false;
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
            var file = GetFileName(new Uri(url));
            var ext = Path.GetExtension(file)?.ToLowerInvariant();
            if (ext == ".js.gz" || ext == ".css.gz" || ext == ".js.zip"
                || ext == ".css.zip" || ext == ".js.bz2" || ext == ".css.bz2")
                return true;
            return false;
        }

        public static void ShutDownPC()
        {
            Log.Debug("Issuing shutdown command");
        }

        public static void SendKeepAlivePing()
        {
            Log.Debug("Keep alive ping...");
        }

        public static void RunCommand(string cmd)
        {
            Log.Debug("Running command: " + cmd);
        }

        public static void RunAntivirus(string cmd, string options, string file)
        {
            Log.Debug("Running antivirus: " + cmd + " " + options + " " + file);
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

        public static void SaveDownloadInfo(string id, SingleSourceHTTPDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.Uri, w);
            WriteStringSafe(info.File, w);
            w.Write(info.ContentLength);
            WriteStateHeaders(info.Headers, w);
            WriteStateCookies(info.Cookies, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static SingleSourceHTTPDownloadInfo? LoadSingleSourceHTTPDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new SingleSourceHTTPDownloadInfo
                {
                    Uri = ReadString(r),
                    File = ReadString(r),
                    ContentLength = r.ReadInt64()
                };
                ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                ReadStateCookies(r, out Dictionary<string, string> cookies);
                info.Cookies = cookies;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }

        public static void SaveDownloadInfo(string id, DualSourceHTTPDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.Uri1, w);
            WriteStringSafe(info.Uri2, w);
            WriteStringSafe(info.File, w);
            w.Write(info.ContentLength);
            WriteStateHeaders(info.Headers1, w);
            WriteStateHeaders(info.Headers2, w);
            WriteStateCookies(info.Cookies1, w);
            WriteStateCookies(info.Cookies2, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static DualSourceHTTPDownloadInfo? LoadDualSourceHTTPDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new DualSourceHTTPDownloadInfo
                {
                    Uri1 = ReadString(r),
                    Uri2 = ReadString(r),
                    File = ReadString(r),
                    ContentLength = r.ReadInt64()
                };
                ReadStateHeaders(r, out Dictionary<string, List<string>> headers1);
                info.Headers1 = headers1;
                ReadStateHeaders(r, out Dictionary<string, List<string>> headers2);
                info.Headers2 = headers2;
                ReadStateCookies(r, out Dictionary<string, string> cookies1);
                info.Cookies1 = cookies1;
                ReadStateCookies(r, out Dictionary<string, string> cookies2);
                info.Cookies2 = cookies2;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
        }

        public static void SaveDownloadInfo(string id, MultiSourceHLSDownloadInfo info)
        {
            using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Create);
            using var w = new BinaryWriter(s);
            WriteStringSafe(info.VideoUri, w);
            WriteStringSafe(info.AudioUri, w);
            WriteStringSafe(info.File, w);
            WriteStateHeaders(info.Headers, w);
            WriteStateCookies(info.Cookies, w);
            //File.WriteAllText(Path.Combine(Config.DataDir, id + ".info"), JsonConvert.SerializeObject(downloadInfo));
        }

        public static MultiSourceHLSDownloadInfo? LoadMultiSourceHLSDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new MultiSourceHLSDownloadInfo
                {
                    VideoUri = ReadString(r),
                    AudioUri = ReadString(r),
                    File = ReadString(r)
                };
                ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                ReadStateCookies(r, out Dictionary<string, string> cookies);
                info.Cookies = cookies;
                return info;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            return null;
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
            WriteStateHeaders(info.Headers, w);
            WriteStateCookies(info.Cookies, w);
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

        public static MultiSourceDASHDownloadInfo? LoadMultiSourceDASHDownloadInfo(string id)
        {
            try
            {
                using var s = new FileStream(Path.Combine(Config.DataDir, id + ".info"), FileMode.Open);
                using var r = new BinaryReader(s);

                var info = new MultiSourceDASHDownloadInfo
                {
                    Url = ReadString(r),
                    File = ReadString(r),
                    AudioFormat = ReadString(r),
                    VideoFormat = ReadString(r),
                    AudioMimeType = ReadString(r),
                    VideoMimeType = ReadString(r),
                    Duration = r.ReadInt64()
                };
                ReadStateHeaders(r, out Dictionary<string, List<string>> headers);
                info.Headers = headers;
                ReadStateCookies(r, out Dictionary<string, string> cookies);
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

        public static IEnumerable<FinishedDownloadEntry> FilterByCategoryOrKeyword(
            IEnumerable<FinishedDownloadEntry> finishedDownloads, string? searchKeyword, Category? category)
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

        public static string GenerateStatusText(InProgressDownloadEntry ent)
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
                    text = ent.ETA;
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

        public static string? ReadString(BinaryReader r)
        {
            var str = r.ReadString();
            if (!string.IsNullOrEmpty(str))
            {
                return str;
            }
            return null;
        }

        public static void WriteStateHeaders(Dictionary<string, List<string>>? headers, BinaryWriter w)
        {
            w.Write(headers == null ? 0 : headers.Count);
            if (headers != null && headers.Count > 0)
            {
                foreach (var key in headers.Keys)
                {
                    w.Write(key);
                    var list = headers[key];
                    w.Write(list.Count);
                    foreach (var item in list)
                    {
                        w.Write(item);
                    }
                }
            }
        }

        public static void ReadStateHeaders(BinaryReader r, out Dictionary<string, List<string>> headers)
        {
            headers = new Dictionary<string, List<string>>();
            var count = r.ReadInt32();
            for (var i = 0; i < count; i++)
            {
                var key = r.ReadString();
                var c = r.ReadInt32();
                var list = new List<string>(c);
                for (var k = 0; k < c; k++)
                {
                    list.Add(r.ReadString());
                }
                headers[key] = list;
            }
        }

        public static void WriteStateCookies(Dictionary<string, string>? cookies, BinaryWriter w)
        {
            w.Write(cookies == null ? 0 : cookies.Count);
            if (cookies != null && cookies.Count > 0)
            {
                foreach (var key in cookies.Keys)
                {
                    w.Write(key);
                    w.Write(cookies[key]);
                }
            }
        }

        public static void ReadStateCookies(BinaryReader r, out Dictionary<string, string> cookies)
        {
            cookies = new Dictionary<string, string>();
            var count = r.ReadInt32();
            for (var i = 0; i < count; i++)
            {
                cookies[r.ReadString()] = r.ReadString();
            }
        }

        public static string FindYDLBinary()
        {
            var executableName = Environment.OSVersion.Platform == PlatformID.Win32NT ? "youtube-dl.exe" : "youtube-dl";
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
            var ydlExec = Helpers.FindExecutableFromSystemPath(executableName);
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

        public static void OpenWindowsProxySettings()
        {
            if (Environment.OSVersion.Version.Major == 10)
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "ms-settings:network-proxy",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
            else
            {
                var psi = new ProcessStartInfo
                {
                    FileName = "rundll32.exe",
                    Arguments = "inetcpl.cpl,LaunchConnectionDialog",
                    UseShellExecute = true
                };
                Process.Start(psi);
            }
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

        public static string GetVideoDownloadFolder()
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
            return Helpers.GetDownloadFolderByFileName("video.mp4");
        }
    }

    public enum NativeHostBrowser
    {
        Chrome, Firefox, MSEdge
    }
}
