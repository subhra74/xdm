using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading;
using System.Diagnostics;
using Newtonsoft.Json;
using Newtonsoft.Json.Serialization;
using XDM.Core.BrowserMonitoring;
using Microsoft.Win32;
using System.Windows.Forms;

#if NET35
using NetFX.Polyfill2;
#else
using System.Collections.Concurrent;
#endif

namespace NativeHost
{
    public class NativeMessagingHostApp
    {
        static bool isFirefox = true;
        static BlockingCollection<byte[]> receivedBrowserMessages = new();
        static BlockingCollection<byte[]> queuedBrowserMessages = new();
        static CamelCasePropertyNamesContractResolver cr = new();

        static void Main(string[] args)
        {
            if(args.Length==1&&(args[0]=="chrome"|| args[0] == "firefox" || args[0] == "edge"))
            {
                try
                {
                    var browser = Browser.Chrome;
                    if (args[0] == "firefox")
                    {
                        browser = Browser.Firefox;
                    }
                    if (args[0] == "edge")
                    {
                        browser = Browser.MSEdge;
                    }
                    InstallNativeMessagingHost(browser);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(ex.ToString());
                }
                return;
            }
            
            try
            {
                var debugMode = Environment.GetEnvironmentVariable("XDM_DEBUG_MODE");
                if (!string.IsNullOrEmpty(debugMode) && debugMode == "1")
                {
                    var logFile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "messaging-log.txt");
                    Trace.Listeners.Add(new TextWriterTraceListener(logFile, "myListener"));
                    Trace.AutoFlush = true;
                }
                Debug("Application_Startup");
                if (args.Length > 0 && args[0].StartsWith("chrome-extension:"))
                {
                    isFirefox = false;
                }
            }
            catch { }
            Debug("Process running from: " + AppDomain.CurrentDomain.BaseDirectory);
#if !NET35
            Debug("Is64BitProcess: " + Environment.Is64BitProcess);
#endif
            try
            {
                var inputReader = Console.OpenStandardInput();
                var outputWriter = Console.OpenStandardOutput();
                try
                {
                    Debug("Trying to open mutex");
                    using var mutex = Mutex.OpenExisting(@"Global\XDM_Active_Instance");
                    Debug("Mutex opened");
                }
                catch
                {
                    Debug("Mutex open failed, spawn xdm process...++");
                    CreateXDMInstance();
                }

                var t1 = new Thread(() =>
                  {
                      Debug("t1 reading messages from stdin sent by browser: ");
                      try
                      {
                          while (true)
                          {
                              //read from process stdin and write to blocking queue,
                              //they will be sent to xdm once pipe handshake complets
                              Debug("Waiting for message - stdin...");
                              var msg = NativeMessageSerializer.ReadMessageBytes(inputReader, false);
                              Debug("Reading message from stdin - size: " + msg.Length);
                              Debug("Stdin - " + Encoding.UTF8.GetString(msg));
                              receivedBrowserMessages.Add(JsonToBinary(msg));
                          }
                      }
                      catch (Exception exx)
                      {
                          Debug(exx.ToString());
                          Environment.Exit(1);
                      }
                  });

                t1.Start();

                var t2 = new Thread(() =>
                  {
                      try
                      {
                          while (true)
                          {
                              //read from blocking queue and write to stdout,
                              //these messages were queued by xdm
                              var msg = queuedBrowserMessages.Take();
                              Debug("Sending to browser: " + Encoding.UTF8.GetString(msg));
                              var json = BinaryToJson(msg);
                              Debug("Sending to browser: ");
                              Debug(Encoding.UTF8.GetString(json));
                              NativeMessageSerializer.WriteMessage(outputWriter, json, false);
                          }
                      }
                      catch (Exception exx)
                      {
                          Debug(exx.ToString());
                          Environment.Exit(1);
                      }
                  });

                t2.Start();
                Debug("Start message proccessing...");
                ProcessMessages();
                Debug("Finished message proccessing.");
            }
            catch (Exception ex)
            {
                Debug(ex.ToString());
            }
        }

        private static void CreateXDMInstance(bool minimized = true)
        {
            try
            {
                var file = Path.Combine(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."),
                         Environment.OSVersion.Platform == PlatformID.Win32NT ? "xdm-app.exe" : "xdm-app");
                Debug("XDM instance creating...1 " + file);
                if (/*isFirefox &&*/ Environment.OSVersion.Platform == PlatformID.Win32NT)
                {
                    MessageBox.Show("Launching XDM...");
                    ProcessStartInfo psi = new()
                    {
                        FileName = "xdm-app://helo",
                        UseShellExecute = true
                    };

                    Debug("XDM instance creating...");
                    Process.Start(psi);

                    //var args = minimized ? " -m" : "";
                    //if (!NativeProcess.Win32CreateProcess(file, $"\"{file}\"{args}"))
                    //{
                    //    Debug("Win32 create process failed!");
                    //}
                }
                else
                {
                    ProcessStartInfo psi = new()
                    {
                        FileName = file,
                        UseShellExecute = true
                    };

                    if (minimized)
                    {
                        psi.Arguments = "-m";
                    }

                    Debug("XDM instance creating...");
                    Process.Start(psi);
                    Debug("XDM instance created");
                }
            }
            catch (Exception ex)
            {
                Debug(ex.ToString());
            }
        }

        private static void ProcessMessages()
        {
            Debug("start");

            try
            {
                NamedPipeClientStream? pipe = null;
                {
                    try
                    {
                        //start handshake with XDM
                        pipe = new NamedPipeClientStream(".", "XDM_Ipc_Browser_Monitoring_Pipe", PipeDirection.InOut, PipeOptions.Asynchronous);
                        Debug("start handshake with XDM");
                        pipe.Connect();

                        //handshake with XDM is complete
                        Debug("handshake with XDM is complete");

                        using var waitHandle = new ManualResetEvent(false);

                        //Direction: XDM ---> NativeHost
                        //Read messages from XDM's named pipe and add them to queuedBrowserMessages
                        var task1 = new Thread(() =>
                         {
                             try
                             {
                                 while (true)
                                 {
                                     var syncMsgBytes = NativeMessageSerializer.ReadMessageBytes(pipe);
                                     Debug("Message received from XDM of size: " + syncMsgBytes.Length);
                                     if (syncMsgBytes.Length == 0)
                                     {
                                         break;
                                     }
                                     Debug("Message from XDM: " + Encoding.UTF8.GetString(syncMsgBytes));
                                     queuedBrowserMessages.Add(syncMsgBytes);
                                 }
                             }
                             catch (Exception ex)
                             {
                                 Debug(ex.ToString(), ex);
                                 queuedBrowserMessages.Add(Encoding.UTF8.GetBytes("{\"appExited\":\"true\"}"));
                             }
                             waitHandle.Set();
                             Debug("Task1 finished");
                         }
                        );

                        //Direction: NativeHost ---> XDM
                        //Take messages from receivedBrowserMessages and write them to XDM's named pipe
                        var task2 = new Thread(() =>
                        {
                            try
                            {
                                while (true)
                                {
                                    Debug("Task2 reading messages queued by browser...");
                                    byte[] syncMsgBytes = receivedBrowserMessages.Take();
                                    if (syncMsgBytes.Length == 2 && (char)syncMsgBytes[0] == '{' && (char)syncMsgBytes[1] == '}')
                                    {
                                        Debug("Task2 empty object received: " + syncMsgBytes.Length + " " + Encoding.UTF8.GetString(syncMsgBytes));
                                        throw new OperationCanceledException("Empty object");
                                    }
                                    Debug("Sending message to XDM...");
                                    NativeMessageSerializer.WriteMessage(pipe, syncMsgBytes);
                                    Debug("Sent message to XDM");
                                }
                            }
                            catch (Exception ex)
                            {
                                Debug(ex.ToString(), ex);
                            }
                            waitHandle.Set();
                            Debug("Task2 finished");
                        }
                        );

                        task1.Start();
                        task2.Start();

                        waitHandle.WaitOne();
                        Debug("Any one task finished");

                    }
                    catch (Exception ex)
                    {
                        Debug(ex.ToString(), ex);
                    }
                    try
                    {
                        pipe?.Close();
                    }
                    catch { }
                    try
                    {
                        pipe?.Dispose();
                    }
                    catch { }
                }
            }
            catch (Exception exxxx)
            {
                Debug(exxxx.ToString());
            }
        }

        private static void Debug(string msg, Exception? ex2 = null)
        {
            Trace.WriteLine($"[xdm-native-messaging-host {DateTime.Now}] {msg}");
            if (ex2 != null)
            {
                Trace.WriteLine($"[xdm-native-messaging-host {DateTime.Now}] {ex2}");
            }
        }

        private static byte[] JsonToBinary(byte[] input)
        {
            var envelop = JsonConvert.DeserializeObject<RawBrowserMessageEnvelop>(Encoding.UTF8.GetString(input),
                        new JsonSerializerSettings
                        {
                            MissingMemberHandling = MissingMemberHandling.Ignore
                        }
                    );
            using var ms = new MemoryStream();
            using var w = new BinaryWriter(ms);
            envelop.Serialize(w);
            w.Close();
            ms.Close();
            return ms.ToArray();
        }

        private static byte[] BinaryToJson(byte[] input)
        {
            var msg = SyncMessage.Deserialize(input);
            var json = JsonConvert.SerializeObject(msg, Formatting.Indented, new JsonSerializerSettings
            {
                ContractResolver = cr
            });
            return Encoding.UTF8.GetBytes(json);
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
            var manifestPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), $"{appName}.json");
            MessageBox.Show("Creating manifest file in: " + manifestPath);
            CreateMessagingHostManifest(browser, appName, manifestPath);
            var regPath = (browser == Browser.Firefox ?
                @"Software\Mozilla\NativeMessagingHosts\" :
                @"SOFTWARE\Google\Chrome\NativeMessagingHosts");
            MessageBox.Show("Reg path: " + regPath);
            using var regKey = Registry.LocalMachine.CreateSubKey(regPath);
            using var key = regKey.CreateSubKey(appName, RegistryKeyPermissionCheck.ReadWriteSubTree);
            key.SetValue(null, manifestPath);
            MessageBox.Show("Installed successfully: " + manifestPath);
        }

        public enum Browser
        {
            Chrome, Firefox, MSEdge
        }
    }
}
