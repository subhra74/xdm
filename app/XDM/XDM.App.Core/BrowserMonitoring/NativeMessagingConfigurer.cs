using Microsoft.Win32;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using TraceLog;
using XDM.Core.Lib.Common;

namespace BrowserMonitoring
{
    internal static class NativeMessagingConfigurer
    {
        private static void CreateMessagingHostManifest(Browser browser, string appName, string manifestPath)
        {
            var allowedExtensions = browser == Browser.Firefox ? new[] {
                        "browser-mon@xdman.sourceforge.net"
                    } : new[] {
                        "chrome-extension://danmljfachfhpbfikjgedlfifabhofcj/",
                        "chrome-extension://dkckaoghoiffdbomfbbodbbgmhjblecj/",
                        "chrome-extension://ejpbcmllmliidhlpkcgbphhmaodjihnc/",
                        "chrome-extension://fogpiboapmefmkbodpmfnohfflonbgig/"
                    };
            using var stream = new FileStream(manifestPath, FileMode.Create);
            using var textWriter = new StreamWriter(stream);
            using var writer = new JsonTextWriter(textWriter);
            writer.Formatting = Formatting.Indented;
            writer.WriteStartObject();
            writer.WritePropertyName("name");
            writer.WriteValue(appName);
            writer.WritePropertyName("description");
            writer.WriteValue("Native messaging host for Xtreme Download Manager");
            writer.WritePropertyName("path");
            writer.WriteValue(Path.Combine(
                Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "MessagingHost"),
                    Environment.OSVersion.Platform == PlatformID.Win32NT ?
                    "xdm-messaging-host.exe" : "xdm-messaging-host"));
            writer.WritePropertyName("type");
            writer.WriteValue("stdio");
            writer.WritePropertyName(browser == Browser.Firefox ? "allowed_extensions" : "allowed_origins");
            writer.WriteStartArray();
            foreach (var extension in allowedExtensions)
            {
                writer.WriteValue(extension);
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
            writer.Close();
        }

        public static void InstallNativeMessagingHost(Browser browser)
        {
            var appName = browser == Browser.Firefox ? "xdmff.native_host" :
                    "xdm_chrome.native_host";
            var os = Environment.OSVersion.Platform;
            if (os == PlatformID.Win32NT)
            {
                var manifestPath = Path.Combine(Config.DataDir, $"{appName}.json");
                CreateMessagingHostManifest(browser, appName, manifestPath);
                var regPath = (browser == Browser.Firefox ?
                    @"Software\Mozilla\NativeMessagingHosts\" :
                    @"SOFTWARE\Google\Chrome\NativeMessagingHosts");
                using var regKey = Registry.CurrentUser.CreateSubKey(regPath);
                using var key = regKey.CreateSubKey(appName, RegistryKeyPermissionCheck.ReadWriteSubTree);
                key.SetValue(null, manifestPath);
            }
            else
            {
#if NET5_0_OR_GREATER
                string manifestPath;
                var home = Environment.GetEnvironmentVariable("HOME")!;
                if (RuntimeInformation.IsOSPlatform(OSPlatform.OSX))
                {
                    manifestPath = Path.Combine(home, browser == Browser.Firefox ?
                            $"Library/Application Support/Mozilla/NativeMessagingHosts/{appName}.json" :
                            $"Library/Application Support/Google/Chrome/NativeMessagingHosts/{appName}.json");
                }
                else
                {
                    manifestPath = Path.Combine(home, browser == Browser.Firefox ?
                            $".mozilla/native-messaging-hosts/{appName}.json" :
                            $".config/google-chrome/NativeMessagingHosts/{appName}.json");
                }
                Log.Debug($"Manifest file: {manifestPath}");
                CreateMessagingHostManifest(browser, appName, manifestPath);
#endif
            }
        }
    }

    public enum Browser
    {
        Chrome, Firefox, MSEdge
    }
}
