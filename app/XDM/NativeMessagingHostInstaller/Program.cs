using Microsoft.Win32;
using Newtonsoft.Json;
using System;
using System.IO;
using System.Windows.Forms;

namespace NativeMessagingHostInstaller
{
    class Program
    {
        static void Main(string[] args)
        {
            try
            {
                var browser = Browser.Chrome;
                if (args.Length > 0)
                {
                    if (args[0] == "firefox")
                    {
                        browser = Browser.Firefox;
                    }
                    if (args[1] == "edge")
                    {
                        browser = Browser.MSEdge;
                    }
                }
                InstallNativeMessagingHost(browser);
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.ToString());
            }
        }

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
                try
                {
                    Directory.CreateDirectory(folder);
                }
                catch (Exception ex)
                {
                    MessageBox.Show("Unable to create required directory: " + ex.Message);
                }
            }
            string aliasPath = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) +
                @"\microsoft\windowsapps\xdm-messaging-host.exe";
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

        public static void InstallNativeMessagingHost(Browser browser)
        {
            var appName = browser == Browser.Firefox ? "xdmff.native_host" :
                    "xdm_chrome.native_host";
            var manifestPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), $"{appName}.json");
            MessageBox.Show("Creating manifest file in: " + manifestPath);
            CreateMessagingHostManifest(browser, appName, manifestPath);
            var regPath = (browser == Browser.Firefox ?
                @"Software\Mozilla\NativeMessagingHosts\" :
                @"SOFTWARE\Google\Chrome\NativeMessagingHosts");
            using var regKey = Registry.LocalMachine.CreateSubKey(regPath);
            using var key = regKey.CreateSubKey(appName, RegistryKeyPermissionCheck.ReadWriteSubTree);
            key.SetValue(null, manifestPath);
        }

        public enum Browser
        {
            Chrome, Firefox, MSEdge
        }
    }
}
