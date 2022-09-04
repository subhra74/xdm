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
            //if (args.Length == 2 && args[0] == "--install-native-messaging-host")
            //{
            //    InstallNativeHost(args);
            //    return;
            //}

            var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
            if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
            {
                var logFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "messaging-log.txt");
                Trace.Listeners.Add(new TextWriterTraceListener(logFile, "myListener"));
                Trace.AutoFlush = true;
            }

            Debug("Application_Startup");

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
                CreateXDMInstance();
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
            //Debug("sending1....");
            //_ipcClient!.Send(new List<string>());
            //Debug("sending2....");
            ReadConfigUpdateFromXDM();
            while (true)
            {
                var bytesFromBrowser = ReadMessageBytes(stdin);
                var msg = JsonConvert.DeserializeObject<DownloadMessage>
                (
                    Encoding.UTF8.GetString(bytesFromBrowser),
                    new JsonSerializerSettings
                    {
                        MissingMemberHandling = MissingMemberHandling.Ignore
                    }
                );
                SendArgsToXDM(msg);
            }
        }

        public static void SendArgsToXDM(DownloadMessage? msg)
        {
            if (msg == null || msg.Url == null)
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

        private static void CreateXDMInstance(bool minimized = true)
        {
            try
            {
                var exe = Path.Combine(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."), "xdm-app.exe");

                ProcessStartInfo psi = new()
                {
                    FileName = exe,
                    UseShellExecute = true,
                    Arguments = "--background"
                };

                Debug("XDM instance creating...");
                Process.Start(psi);
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

        //private static void InstallNativeHost(string[] args)
        //{
        //    var browser = Browser.Chrome;
        //    if (args[1] == "firefox")
        //    {
        //        browser = Browser.Firefox;
        //    }
        //    if (args[1] == "ms-edge")
        //    {
        //        browser = Browser.MSEdge;
        //    }
        //    NativeMessagingHostConfigurer.InstallNativeMessagingHostForWindows(browser);
        //}
    }
}
