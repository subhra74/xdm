using Microsoft.Win32;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace NativeMessaging
{
    public static class NativeMessagingHostConfigurer
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
            var folder = Path.GetDirectoryName(manifestPath)!;
            if (!Directory.Exists(folder))
            {
                Directory.CreateDirectory(folder);
            }
            string aliasPath = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) +
                @"\microsoft\windowsapps\xdm-app-host.exe";
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
            writer.WriteValue(aliasPath);
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

        public static void InstallNativeMessagingHostForWindows(Browser browser)
        {
            var appName = browser == Browser.Firefox ? "xdmff.native_host" :
                    "xdm_chrome.native_host";
            var manifestPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), $"{appName}.json");
            CreateMessagingHostManifest(browser, appName, manifestPath);
            var regPath = (browser == Browser.Firefox ?
                @"Software\Mozilla\NativeMessagingHosts\" :
                @"SOFTWARE\Google\Chrome\NativeMessagingHosts");
            using var regKey = Registry.LocalMachine.CreateSubKey(regPath);
            using var key = regKey.CreateSubKey(appName, RegistryKeyPermissionCheck.ReadWriteSubTree);
            key.SetValue(null, manifestPath);
        }
    }

    public enum Browser
    {
        Chrome, Firefox, MSEdge
    }
}
