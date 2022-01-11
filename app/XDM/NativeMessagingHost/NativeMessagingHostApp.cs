using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Threading;
using System.Diagnostics;
using Newtonsoft.Json;
using BrowserMonitoring;
using Newtonsoft.Json.Serialization;

#if NET35
using NetFX.Polyfill;
#else
using System.Collections.Concurrent;
#endif

namespace NativeHost
{
    public class NativeMessagingHostApp
    {
        static BlockingCollection<byte[]> receivedBrowserMessages = new();
        static BlockingCollection<byte[]> queuedBrowserMessages = new();
        static CamelCasePropertyNamesContractResolver cr = new();
        static StreamWriter log = new StreamWriter(new FileStream(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "native-host.log"), FileMode.Create));
        static void Main(string[] args)
        {
            //var json = BinaryToJson(new byte[0]);
#if !NET35
            Debug(Environment.Is64BitProcess+"");
#endif
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
            Debug("next");
            try
            {


                var inputReader = Console.OpenStandardInput();
                var outputWriter = Console.OpenStandardOutput();
                var t1 = new Thread(() =>
                  {
                      try
                      {
                          while (true)
                          {
                              //read from process stdin and write to blocking queue,
                              //they will be sent to xdm once pipe handshake complets
                              var msg = ReadMessageBytes(inputReader);
                              Debug(Encoding.UTF8.GetString(msg));
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
                              var msg = queuedBrowserMessages.Take();//doesn't make much sense to it async
                              var json = BinaryToJson(msg);
                              Debug(Encoding.UTF8.GetString(json));
                              WriteMessage(outputWriter, json);
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

        private static void CreateXDMInstance()
        {
            try
            {
                Debug("XDM instance creating...1");
                ProcessStartInfo psi = new()
                {
                    FileName = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "XDM.Wpf.UI.exe"),
                    UseShellExecute = true,
                    Arguments = "-m"
                };

                Debug("XDM instance creating...");
                Process.Start(psi);
                Debug("XDM instance created");
            }
            catch (Exception ex)
            {
                Debug(ex.ToString());
            }
        }

        private static void ProcessMessages()
        {
            Debug("Log start");

            try
            {
                NamedPipeServerStream inPipe = null;
                NamedPipeClientStream outPipe = null;
                while (true)
                {
                    try
                    {
                        var pipeName = Guid.NewGuid().ToString();
                        inPipe = new NamedPipeServerStream(pipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.WriteThrough);

                        //start handshake with XDM
                        outPipe = new NamedPipeClientStream(".", "XDM_Ipc_Browser_Monitoring_Pipe", PipeDirection.Out);
                        Debug("start handshake with XDM");
                        outPipe.Connect();
                        WriteMessage(outPipe, pipeName);
                        Debug("pipename: " + pipeName);

                        inPipe.WaitForConnection();
                        var syncMsgBytes = ReadMessageBytes(inPipe);
                        Debug("No task message size: " + syncMsgBytes.Length);

                        queuedBrowserMessages.Add(syncMsgBytes);

                        //handshake with XDM is complete
                        Debug("handshake with XDM is complete");

                        using var waitHandle = new ManualResetEvent(false);

                        //queue messages from xdm pipe for browser
                        var task1 = new Thread(() =>
                         {
                             try
                             {
                                 while (true)
                                 {
                                     var syncMsgBytes = ReadMessageBytes(inPipe);
                                     //Debug("Task1 message size: " + syncMsgBytes.Length);
                                     if (syncMsgBytes.Length == 0)
                                     {
                                         break;
                                     }
                                     queuedBrowserMessages.Add(syncMsgBytes);
                                 }
                             }
                             catch (Exception ex)
                             {
                                 Debug(ex.ToString());
                                 queuedBrowserMessages.Add(Encoding.UTF8.GetBytes("{\"appExited\":\"true\"}"));
                             }
                             waitHandle.Set();
                             Debug("Task1 finished");
                         }
                        );

                        //queue messages to xdm pipe from browser
                        var task2 = new Thread(() =>
                        {
                            try
                            {
                                while (true)
                                {
                                    byte[] syncMsgBytes = null;
                                    Debug("Task2 reading from browser stdin...");
                                    syncMsgBytes = receivedBrowserMessages.Take();
                                    if (syncMsgBytes.Length == 2 && (char)syncMsgBytes[0] == '{' && (char)syncMsgBytes[1] == '}')
                                    {
                                        Debug("Task2 empty object received: " + syncMsgBytes.Length + " " + Encoding.UTF8.GetString(syncMsgBytes));
                                        throw new OperationCanceledException("Empty object");
                                    }
                                    //Debug("Task2 message size fron browser stdin: " + syncMsgBytes.Length);
                                    //Debug(Encoding.UTF8.GetString(syncMsgBytes));
                                    WriteMessage(outPipe, syncMsgBytes);
                                }
                            }
                            catch (Exception ex)
                            {
                                Debug(ex.ToString());
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
                        Debug(ex.ToString());
                    }

                    try
                    {
                        inPipe.Disconnect();
                    }
                    catch { }
                    try
                    {
                        outPipe.Dispose();
                    }
                    catch { }
                    try
                    {
                        inPipe.Dispose();
                    }
                    catch { }
                }
            }
            catch (Exception exxxx)
            {
                Debug(exxxx.ToString());
            }
        }

        private static void Debug(string msg)
        {
            try
            {
                log.WriteLine(msg);
                log.Flush();
                //File.AppendAllText(@"c:\log.txt", msg + "\r\n");
                Trace.WriteLine($"[{DateTime.Now}][NativeHost] {msg}");
            }
            catch(Exception ex)
            {
                log.WriteLine(ex.ToString());
                log.Flush();
            }
        }

        private static void WriteMessage(Stream pipe, string message)
        {
            var msgBytes = Encoding.UTF8.GetBytes(message);
            WriteMessage(pipe, msgBytes);
        }

        private static void WriteMessage(Stream pipe, byte[] msgBytes)
        {
            pipe.Write(BitConverter.GetBytes(msgBytes.Length), 0, 4);
            pipe.Write(msgBytes, 0, msgBytes.Length);
            pipe.Flush();
        }

        private static byte[] ReadMessageBytes(Stream pipe)
        {
            var b4 = new byte[4];
            ReadFully(pipe, b4, 4);
            var syncLength = BitConverter.ToInt32(b4, 0);
            var bytes = new byte[syncLength];
            ReadFully(pipe, bytes, syncLength);
            return bytes;
        }

        private static string ReadMessageString(Stream pipe)
        {
            var b4 = new byte[4];
            ReadFully(pipe, b4, 4);
            var syncLength = BitConverter.ToInt32(b4, 0);
            var bytes = new byte[syncLength];
            ReadFully(pipe, bytes, syncLength);
            return Encoding.UTF8.GetString(bytes);
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
    }
}
