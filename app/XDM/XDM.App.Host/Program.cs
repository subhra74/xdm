using Microsoft.Win32;
using NativeMessaging;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace XDM.App.Host
{
    //This will run on Windows only, for Linux there is a Python script...
    class Program
    {
        private static IpcClient? _ipcClient;
        private static Stream? stdin;
        private static Stream? stdout;
        static void Main(string[] args)
        {
            Trace.WriteLine($"[xdm-native-messaging-host] startup");

            var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
            if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
            {
                var logFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "messaging-log.txt");
                Trace.Listeners.Add(new TextWriterTraceListener(logFile, "myListener"));
                Trace.AutoFlush = true;
            }

            Debug("Application_Startup");

            var isFirefox = true;
            if (args.Length > 0 && args[0].StartsWith("chrome-extension:"))
            {
                isFirefox = false;
            }

            stdin = Console.OpenStandardInput();
            stdout = Console.OpenStandardOutput();

            //In Python native host just check for tcp socket running on 8597, if not launch XDM
            try
            {
                Debug("Trying to open mutex");
                using var mutex = Mutex.OpenExisting(@"Global\XDM_Active_Instance");
                Debug("Mutex opened");
            }
            catch
            {
                Debug("Mutex open failed, spawning xdm process...");
                CreateXDMInstance(isFirefox);
            }

            var connected = false;
            for (var i = 0; i < 5; i++)
            {
                try
                {
                    Debug("Trying to connect with XDM...");
                    ConnectWithXDM();
                    connected = true;
                    break;
                }
                catch
                {
                    Debug("Unable to connect to XDM retry in 1 sec...");
                    Thread.Sleep(1000);
                }
            }

            if (!connected)
            {
                Debug("Unable to connect to XDM after 5 attempts, giving up...");
                Environment.Exit(1);
            }

            try
            {
                ReadConfigUpdateFromXDM();
                while (true)
                {
                    var bytesFromBrowser = ReadMessageBytes(stdin);
                    var text = Encoding.UTF8.GetString(bytesFromBrowser);
                    Debug(text);
                    var msg = JsonConvert.DeserializeObject<DownloadMessage>
                    (
                        text,
                        new JsonSerializerSettings
                        {
                            MissingMemberHandling = MissingMemberHandling.Ignore
                        }
                    );
                    SendArgsToXDM(msg);
                }
            }
            catch (Exception ex)
            {
                Debug(ex.Message, ex);
                throw;
            }
        }

        public static void SendArgsToXDM(DownloadMessage? msg)
        {
            Debug("SendArgsToXDM...");

            if (msg == null)
            {
                return;
            }

            if (msg.Vid != null)
            {
                Debug($"vid: {msg.Vid}");
                SendVideoId(msg.Vid);
                return;
            }

            if (msg.Clear.HasValue && msg.Clear.Value)
            {
                SendClearCmd();
                return;
            }

            if (msg.TabUpdate != null)
            {
                SendTabUpdate(msg.TabUpdate);
                return;
            }

            if (msg.RequestData != null)
            {
                SendHeaderData(msg.RequestData);
                return;
            }

            if (msg.Url == null)
            {
                return;
            }

            var arguments = new List<string>();
            if (msg.Cookie != null)
            {
                arguments.Add("--cookie");
                arguments.Add(msg.Cookie);
            }

            if (msg.Headers != null)
            {
                foreach (var header in msg.Headers)
                {
                    arguments.Add("-H");
                    arguments.Add(header);
                }
            }

            if (msg.FileSize > 0)
            {
                arguments.Add("--known-file-size");
                arguments.Add(msg.FileSize + "");
            }

            if (!string.IsNullOrEmpty(msg.MimeType))
            {
                arguments.Add("--known-mime-type");
                arguments.Add(msg.MimeType!);
            }

            if (!string.IsNullOrEmpty(msg.FileName))
            {
                arguments.Add("--output");
                arguments.Add(msg.FileName!);
            }
            arguments.Add(msg.Url);
            _ipcClient!.Send(arguments);
        }

        private static void SendHeaderData(RequestData data)
        {
            Debug("Going to send media...");
            if (data.Url == null)
            {
                return;
            }
            var arguments = new List<string>();
            arguments.Add("--media");
            if (data.RequestHeaders != null)
            {
                foreach (var header in data.RequestHeaders)
                {
                    foreach (var value in header.Value)
                    {
                        arguments.Add("-H");
                        arguments.Add(header.Key + ":" + value);
                    }
                }
            }
            if (data.ResponseHeaders != null)
            {
                var fileSize = GetFileSize(data.ResponseHeaders);
                var mimeType = GetMediaType(data.ResponseHeaders);
                Debug("Mime: " + mimeType);
                if (fileSize > 0)
                {
                    arguments.Add("--known-file-size");
                    arguments.Add(fileSize + "");
                }
                if (!string.IsNullOrEmpty(mimeType))
                {
                    arguments.Add("--known-mime-type");
                    arguments.Add(mimeType!);
                }
            }
            if (!string.IsNullOrEmpty(data.File))
            {
                arguments.Add("--output");
                arguments.Add(data.File);
            }
            if (!string.IsNullOrEmpty(data.TabUrl))
            {
                arguments.Add("--tab-url");
                arguments.Add(data.TabUrl);
            }
            arguments.Add(data.Url);
            Debug(string.Join(",", arguments));
            _ipcClient!.Send(arguments);
        }

        private static void SendVideoId(string vid)
        {
            Debug("########Going to send vid id...");
            if (string.IsNullOrEmpty(vid))
            {
                return;
            }
            var arguments = new List<string>();
            arguments.Add("--media-vid");
            arguments.Add(vid);
            Debug(string.Join(",", arguments));
            _ipcClient!.Send(arguments);
        }

        private static void SendClearCmd()
        {
            Debug("########Going to send clear command...");
            var arguments = new List<string>();
            arguments.Add("--media-clear");
            Debug(string.Join(",", arguments));
            _ipcClient!.Send(arguments);
        }

        private static void SendTabUpdate(TabInfo tab)
        {
            Debug("########Going to send tab update...");
            if (tab.Url == null || tab.Title == null)
            {
                return;
            }
            var arguments = new List<string>();
            arguments.Add("--media-tab-url");
            arguments.Add(tab.Url);
            arguments.Add("--media-tab-title");
            arguments.Add(tab.Title);
            Debug(string.Join(",", arguments));
            _ipcClient!.Send(arguments);
        }

        public static void ReadConfigUpdateFromXDM()
        {
            new Thread(() =>
            {
                try
                {
                    while (true)
                    {
                        Debug("Receive config from _XDM...");
                        var lines = _ipcClient!.Receive();
                        Debug(string.Join("\n", lines));
                        Debug("Received config from _XDM...");
                        SendToBrowser(Encoding.UTF8.GetBytes(string.Join("\n", lines)));
                    }
                }
                catch (Exception ex)
                {
                    Debug("Error receving message from XDM", ex);
                    Environment.Exit(1);
                }
            }).Start();
        }

        public static void SendToBrowser(byte[] msgBytes)
        {
            stdout!.Write(BitConverter.GetBytes(msgBytes.Length), 0, 4);
            stdout!.Write(msgBytes, 0, msgBytes.Length);
            stdout!.Flush();
        }

        public static byte[] ReadMessageBytes(Stream stdin)
        {
            var b4 = new byte[4];
            ReadFully(stdin, b4, 4);
            var syncLength = BitConverter.ToInt32(b4, 0);
            if (syncLength > 32 * 1024 * 1024)
            {
                throw new ArgumentException($"Message length too long: {syncLength}");
            }
            var bytes = new byte[syncLength];
            ReadFully(stdin, bytes, syncLength);
            return bytes;
        }

        private static void ReadFully(Stream stream, byte[] buf, int bytesToRead)
        {
            var rem = bytesToRead;
            var index = 0;
            while (rem > 0)
            {
                var c = stream.Read(buf, index, rem);
                if (c == 0) throw new IOException("Unexpected EOF");
                index += c;
                rem -= c;
            }
        }

        private static void ConnectWithXDM()
        {
            _ipcClient = new IpcClient();
            _ipcClient.Connect(8597);
        }

        private static void CreateXDMInstance(bool isFirefox, bool minimized = true)
        {
            try
            {

#if NET6_0
                var exe = Path.Combine(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."), "xdm-app");
                ProcessStartInfo psi = new()
                {
                    FileName = exe,
                    UseShellExecute = true,
                    Arguments = "--background"
                };
                psi.EnvironmentVariables.Add("GTK_USE_PORTAL", "1");

                Debug("XDM instance creating...");
                Process.Start(psi);
#else
                var exe = Path.GetFullPath(
                    new Uri(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "XDM.Wpf.UI", "xdm-app.exe")).LocalPath);
                Debug(exe);
                if (isFirefox)
                {
                    if (!Win32NativeProcess.Win32CreateProcess(exe, $"\"{exe}\" --background"))
                    {
                        Debug("Win32 create process failed!");
                    }
                }
                else
                {
                    ProcessStartInfo psi = new()
                    {
                        FileName = exe,
                        UseShellExecute = true,
                        Arguments = "--background"
                    };

                    Debug("XDM instance creating...");
                    Process.Start(psi);
                }
#endif
            }
            catch (Exception ex)
            {
                Debug(ex.ToString());
            }
        }

        private static void Debug(string msg, Exception? ex2 = null)
        {
            Trace.WriteLine($"[xdm-native-messaging-host {DateTime.Now}] {msg}");
            if (ex2 != null)
            {
                Trace.WriteLine($"[xdm-native-messaging-host {DateTime.Now}] {ex2}");
            }
            Console.Error.WriteLine(msg);
            Console.Error.Flush();
        }

        private static long GetFileSize(Dictionary<string, List<string>> headers)
        {
            if (headers == null) return -1;
            try
            {
                foreach (var key in headers.Keys)
                {
                    if (key.ToUpperInvariant() == "CONTENT-LENGTH")
                    {
                        return headers[key].Count > 0 ? Int64.Parse(headers[key][0].Trim()) : -1;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug(ex.Message, ex);
            }
            return -1;
        }

        private static string? GetMediaType(Dictionary<string, List<string>> headers)
        {
            if (headers == null) return null;
            try
            {
                foreach (var key in headers.Keys)
                {
                    if (key.ToUpperInvariant() == "CONTENT-TYPE")
                    {
                        return headers[key].Count > 0 ? headers[key][0].Trim() : null;
                    }
                }
            }
            catch (Exception ex)
            {
                Debug(ex.Message, ex);
            }
            return null;
        }

        private static string? GetReferer(Dictionary<string, string>? headers)
        {
            if (headers == null) return null;
            try
            {
                foreach (var key in headers.Keys)
                {
                    if (key.ToUpperInvariant() == "REFERER")
                    {
                        return headers[key].Trim();
                    }
                }
            }
            catch (Exception ex)
            {
                Debug(ex.Message, ex);
            }
            return null;
        }
    }
}
