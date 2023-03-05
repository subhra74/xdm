//using Microsoft.Win32;
//using Newtonsoft.Json;
//using System;
//using System.Collections.Generic;
//using System.IO;
//using System.Runtime.InteropServices;
//using System.Text;
//using TraceLog;
//using XDM.Core;

//namespace XDM.Core.BrowserMonitoring
//{
//    public static class NativeMessagingHostConfigurer
//    {
//        private static string CalculateExtensionId(string path)
//        {
//#if WINDOWS
//            if (path[0] >= 'a' && path[1] <= 'z' && path[1] == ':')
//            {
//                path = path[0].ToString().ToUpper() + path.Substring(1);
//            }
//#endif
//            var text = path;
//            var crypt = System.Security.Cryptography.SHA256.Create();
//            string hash = string.Empty;
//#if WINDOWS
//            byte[] crypto = crypt.ComputeHash(Encoding.Unicode.GetBytes(text));
//#else
//            byte[] crypto = crypt.ComputeHash(Encoding.UTF8.GetBytes(text));
//#endif
//            foreach (byte theByte in crypto)
//            {
//                var hex = theByte.ToString("x2");
//                hash += hex;
//            }
//            var ext = string.Empty;
//            foreach (var ch in hash)
//            {
//                var x = System.Convert.ToInt32(ch.ToString(), 16);
//                char c = (char)(x + 97);
//                ext += c;// theByte.ToString("x2");
//            }
//            return ext.Substring(0, 32);
//        }

//        private static string GetExecutablePath(bool msix = false)
//        {
//            if (msix)
//            {
//                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
//                        @"microsoft\windowsapps\xdm-app-host.exe");
//            }
//            return Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "XDM.App.Host" + Path.DirectorySeparatorChar + "xdm-app-host" + (
//                Environment.OSVersion.Platform == PlatformID.Win32NT ? ".exe" : string.Empty));
//            //#if NET472
//            //            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
//            //                    @"microsoft\windowsapps\xdm-app-host.exe");
//            //#else
//            //            return Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "XDM.App.Host" + Path.DirectorySeparatorChar + "xdm-app-host" + (
//            //                Environment.OSVersion.Platform == PlatformID.Win32NT ? ".exe" : string.Empty));
//            //#endif
//        }

//        private static string GetFirefoxBatchPath()
//        {
//            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "xdm-app-host.bat");
//        }

//        private static void CreateMessagingHostManifest(Browser browser, string appName,
//            string manifestPath, bool msix = false)
//        {
//            Log.Debug("msix: " + msix);
//            Log.Debug("Manifest path: " + manifestPath);
//            var extensions = new HashSet<string> { browser == Browser.Firefox ? "xdm-integration-module@subhra74.github.io" : "chrome-extension://akdmdglbephckgfmdffcdebnpjgamofc/" };
//            if (browser == Browser.Chrome)
//            {
//                Log.Debug("Configuring for chrome");
//                var extId = CalculateExtensionId(Path.Combine(
//                    msix ? Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments) :
//                    AppDomain.CurrentDomain.BaseDirectory
//                    , "chrome-extension"));
//                Log.Debug("ExtensionId: " + extId);
//                extensions.Add($"chrome-extension://{extId}/");
//                try
//                {
//                    var file = Path.Combine(Config.AppDir, "extension.txt");
//                    if (File.Exists(file))
//                    {
//                        foreach (var line in File.ReadAllLines(file))
//                        {
//                            extensions.Add(line);
//                        }
//                    }
//                }
//                catch { }
//            }
//            var folder = Path.GetDirectoryName(manifestPath)!;
//            if (!Directory.Exists(folder))
//            {
//                Directory.CreateDirectory(folder);
//            }
//            string pathToExe = GetExecutablePath(msix);
//            Log.Debug(pathToExe);

//            if (msix)
//            {
//                string? batchFilePath = null;
//                if (browser == Browser.Firefox)
//                {
//                    batchFilePath = GetFirefoxBatchPath();
//                    File.WriteAllText(batchFilePath, $"@echo off\r\n\"{pathToExe}\"");
//                    pathToExe = batchFilePath;
//                }

//            }
//            using var stream = new FileStream(manifestPath, FileMode.Create);
//            using var textWriter = new StreamWriter(stream);
//            using var writer = new JsonTextWriter(textWriter);
//            writer.Formatting = Formatting.Indented;
//            writer.WriteStartObject();
//            writer.WritePropertyName("name");
//            writer.WriteValue(appName);
//            writer.WritePropertyName("description");
//            writer.WriteValue("Native messaging host for Xtreme Download Manager");
//            writer.WritePropertyName("path");
//            writer.WriteValue(pathToExe);
//            writer.WritePropertyName("type");
//            writer.WriteValue("stdio");
//            writer.WritePropertyName(browser == Browser.Firefox ? "allowed_extensions" : "allowed_origins");
//            writer.WriteStartArray();
//            foreach (var extension in extensions)
//            {
//                if (!string.IsNullOrEmpty(extension))
//                {
//                    writer.WriteValue(extension);
//                }
//            }
//            writer.WriteEndArray();
//            writer.WriteEndObject();
//            writer.Close();
//        }

//#if WINDOWS
//        private static bool IsMessagingHostAlreadyInstalledForChrome()
//        {
//            try
//            {
//                using var regKey = Registry.CurrentUser.OpenSubKey(@"SOFTWARE\Google\Chrome\NativeMessagingHosts\xdm_chrome.native_host");
//                var path = (string)regKey.GetValue(null);
//                if (path == Path.Combine(AppDomain.CurrentDomain.BaseDirectory, Path.Combine("XDM.App.Host", "xdm_chrome.native_host.json")))
//                {
//                    return true;
//                }
//            }
//            catch { }
//            return false;
//        }

//        private static bool IsMessagingHostAlreadyInstalledForFirefox()
//        {
//            try
//            {
//                using var regKey = Registry.CurrentUser.OpenSubKey(@"Software\Mozilla\NativeMessagingHosts\xdmff.native_host");
//                var path = (string)regKey.GetValue(null);
//                if (path == Path.Combine(AppDomain.CurrentDomain.BaseDirectory, Path.Combine("XDM.App.Host", "xdmff.native_host.json")))
//                {
//                    return true;
//                }
//            }
//            catch { }
//            return false;
//        }

//        public static void InstallNativeMessagingHostForWindows(Browser browser, bool msix = false)
//        {
//            var appName = browser == Browser.Firefox ? "xdmff.native_host" :
//                    "xdm_chrome.native_host";
//            var manifestPath = msix ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
//                $"{appName}.json") : Path.Combine(Config.AppDir, $"{appName}.json");
//            CreateMessagingHostManifest(browser, appName, manifestPath, msix);
//            var regPath = browser == Browser.Firefox ?
//                @"Software\Mozilla\NativeMessagingHosts\" :
//                @"Software\Google\Chrome\NativeMessagingHosts";
//            using var hive = msix ? Registry.LocalMachine : Registry.CurrentUser;
//            using var regKey = hive.CreateSubKey(regPath);
//            using var key = regKey.CreateSubKey(appName, RegistryKeyPermissionCheck.ReadWriteSubTree);
//            key.SetValue(null, manifestPath);
//        }
//#endif

//#if LINUX
//        public static void InstallNativeMessagingHostForLinux(Browser browser)
//        {
//            var appName = browser == Browser.Firefox ? "xdmff.native_host" :
//                    "xdm_chrome.native_host";
//            string manifestPath;
//            var home = Environment.GetEnvironmentVariable("HOME")!;
//            if (home == null)
//            {
//                home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
//            }
//            if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
//            {
//                manifestPath = Path.Combine(home, browser == Browser.Firefox ?
//                        $"Library/Application Support/Mozilla/NativeMessagingHosts/{appName}.json" :
//                        $"Library/Application Support/Google/Chrome/NativeMessagingHosts/{appName}.json");
//            }
//            else
//            {
//                manifestPath = Path.Combine(home, browser == Browser.Firefox ?
//                        $".mozilla/native-messaging-hosts/{appName}.json" :
//                        $"{GetLinuxBrowserExecutablePath(browser)}/{appName}.json");
//            }
//            CreateMessagingHostManifest(browser, appName, manifestPath);
//        }

//        public static string? GetLinuxBrowserExecutablePath(Browser browser)
//        {
//            switch (browser)
//            {
//                case Browser.Chrome:
//                    return ".config/google-chrome/NativeMessagingHosts";
//                case Browser.Chromium:
//                    return ".config/chromium/NativeMessagingHosts";
//                case Browser.MSEdge:
//                    return ".config/microsoft-edge/NativeMessagingHosts";
//                case Browser.Brave:
//                    return ".config/BraveSoftware/Brave-Browser/NativeMessagingHosts";
//                case Browser.Vivaldi:
//                    return ".config/vivaldi/NativeMessagingHosts";
//                case Browser.Opera:
//                    return ".config/opera/NativeMessagingHosts";
//                default:
//                    break;
//            }
//            return null;
//        }
//#endif

//        public static IEnumerable<string> GetBrowserExecutableName(Browser browser)
//        {
//            switch (browser)
//            {
//                case Browser.Chrome:
//                    return new string[] { "chrome", "google-chrome", "google-chrome-stable" };
//                case Browser.Firefox:
//                    return new string[] { "firefox" };
//                case Browser.MSEdge:
//                    return new string[] { "msedge" };
//                case Browser.Brave:
//                    return new string[] { "brave", "brave-browser", "brave-browser-stable" };
//                case Browser.Vivaldi:
//                    return new string[] { "vivaldi", "vivaldi-browser", "vivaldi-browser-stable" };
//                case Browser.Opera:
//                    return new string[] { "opera", "opera-browser", "opera-browser-stable" };
//                default:
//                    break;
//            }
//            return new string[0];
//        }

//    }

//    public enum Browser
//    {
//        Chrome, Firefox, MSEdge, Brave, Vivaldi, Opera, Chromium
//    }
//}
