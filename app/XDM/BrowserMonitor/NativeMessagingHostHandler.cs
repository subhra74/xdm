using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.IO.Pipes;
using System.IO;
using XDM.Core.Lib.Common;
using System.Threading;
#if NET35
using NetFX.Polyfill;
#else
using System.Collections.Concurrent;
#endif
using TraceLog;

namespace BrowserMonitoring
{
    public class NativeMessagingHostHandler : IDisposable
    {
        private int MaxPipeInstance = 254;
        private static readonly string PipeName = "XDM_Ipc_Browser_Monitoring_Pipe";
        private List<NativeMessagingHostChannel> connectedChannels = new();
        //private List<NamedPipeServerStream> inPipes = new();
        //private Dictionary<NamedPipeServerStream, NamedPipeClientStream> inOutMap = new();
        private readonly IApp app;
        private static Mutex globalMutex;
        // private readonly BlockingCollection<byte[]> Messages = new();
        //private Thread WriterThread;
        private Thread listenerThread;

        public static void EnsureSingleInstance(IApp app)
        {
            try
            {
                using var mutex = Mutex.OpenExisting(@"Global\XDM_Active_Instance");
                throw new InstanceAlreadyRunningException(@"XDM instance already running, Mutex exists 'Global\XDM_Active_Instance'");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Exception in NativeMessagingHostHandler ctor");
                if (ex is InstanceAlreadyRunningException)
                {
                    if (app.Args != null && app.Args.Length > 0)
                    {
                        Log.Debug(ex, "Sending args to running instance");
                        SendArgsToRunningInstance(app.Args);
                        Environment.Exit(0);
                    }
                    throw;
                }
            }
            globalMutex = new Mutex(true, @"Global\XDM_Active_Instance");
        }

        public NativeMessagingHostHandler(IApp app)
        {
            this.app = app;
            EnsureSingleInstance(this.app);
        }

        public void BroadcastConfig()
        {
            var bytes = GetSyncBytes(app);
            lock (this)
            {
                foreach (var channel in connectedChannels)
                {
                    try
                    {
                        channel.Publish(bytes);
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                }
            }
            //Messages.Add(bytes);
        }

        public void StartPipedChannel()
        {
            listenerThread = new Thread(() =>
              {
                  while (true)
                  {
                      var pipe =
                            new NamedPipeServerStream(PipeName,
                            PipeDirection.InOut, NamedPipeServerStream.MaxAllowedServerInstances,
                            PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                      Log.Debug("Waiting for native host pipe...");
                      pipe.WaitForConnection();
                      Log.Debug("Pipe request received");
                      lock (connectedChannels)
                      {
                          var channel = CreateChannel(pipe);
                          connectedChannels.Add(channel);
                          channel.Start(GetSyncBytes(app));
                      }
                  }
              });
            listenerThread.Start();
        }

        private NativeMessagingHostChannel CreateChannel(NamedPipeServerStream pipe)
        {
            var channel = new NativeMessagingHostChannel(pipe);
            channel.MessageReceived += (sender, args) =>
            {
                try
                {
                    using var br = new BinaryReader(new MemoryStream(args.Data));
                    var envelop = RawBrowserMessageEnvelop.Deserialize(br);
                    BrowserMessageHandler.Handle(app, envelop);
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.ToString());
                }
            };
            channel.Disconnected += (sender, bytes) =>
            {
                lock (connectedChannels)
                {
                    connectedChannels.Remove((NativeMessagingHostChannel)sender);
                }
            };
            return channel;
        }

        //public void StartPipedChannel()
        //{
        //    WriterThread = new Thread(() =>
        //     {
        //         while (true)
        //         {
        //             //Log.Debug("Total messages to be sent to native host: " + Messages.Count);
        //             var bytes = Messages.Take();
        //             foreach (var key in inOutMap.Keys)
        //             {
        //                 //Log.Debug("Sending message to native host");
        //                 try
        //                 {
        //                     var outpipe = inOutMap[key];
        //                     NativeMessageSerializer.WriteMessage(outpipe, bytes);
        //                     //Log.Debug("Send message to native host successfully");
        //                 }
        //                 catch (Exception ex)
        //                 {
        //                     Log.Debug(ex, "Send message to native host failed");
        //                 }
        //             }
        //         }
        //     });
        //    WriterThread.Start();
        //    new Thread(() =>
        //    {
        //        try
        //        {
        //            if (inPipes.Count == MaxPipeInstance)
        //            {
        //                Log.Debug("Max pipe count of " + MaxPipeInstance + " is reached");
        //                return;
        //            }
        //            var inPipe =
        //                    new NamedPipeServerStream(PipeName,
        //                    PipeDirection.In, NamedPipeServerStream.MaxAllowedServerInstances,
        //                    PipeTransmissionMode.Byte, PipeOptions.WriteThrough);
        //            inPipes.Add(inPipe);
        //            var first = true;
        //            while (true)
        //            {
        //                Log.Debug("Waiting for native host pipe...");
        //                inPipe.WaitForConnection();
        //                Log.Debug("Pipe request received");

        //                if (first)
        //                {
        //                    Log.Debug("Creating one more additional pipe");
        //                    StartPipedChannel();
        //                    first = false;
        //                }

        //                try
        //                {
        //                    ConsumePipe(inPipe);
        //                }
        //                catch (Exception e)
        //                {
        //                    inPipe.Disconnect();
        //                    Log.Debug(e, "Error in message exchange");
        //                }
        //                Log.Debug("Terminated message exchange, will reuse the pipe");
        //            }
        //        }
        //        catch (Exception ex)
        //        {
        //            Log.Debug(ex, "Error in message exchange flow");
        //        }
        //    }).Start();
        //}

        //private void ConsumePipe(NamedPipeServerStream inPipe)
        //{
        //    try
        //    {
        //        Log.Debug("Initiate message handshake");
        //        var clientPipeName = Encoding.UTF8.GetString(NativeMessageSerializer.ReadMessageBytes(inPipe));
        //        Log.Debug("Client pipe: " + clientPipeName);
        //        if (clientPipeName.StartsWith("XDM-APP-"))
        //        {
        //            var command = NativeMessageSerializer.ReadMessageBytes(inPipe);
        //            var args = ArgsProcessor.ParseArgs(Encoding.UTF8.GetString(command).Split('\r'));
        //            ArgsProcessor.Process(app, args);
        //            return;
        //        }
        //        var outPipe = new NamedPipeClientStream(".", clientPipeName, PipeDirection.Out);
        //        outPipe.Connect();
        //        SendConfig(outPipe);
        //        inOutMap[inPipe] = outPipe;
        //        Log.Debug("Message handshake completed");
        //        while (true)
        //        {
        //            var text = NativeMessageSerializer.ReadMessageBytes(inPipe);
        //            using var ms = new MemoryStream(text);
        //            using var br = new BinaryReader(ms);
        //            // Log.Debug("{Text}", text);
        //            var envelop = RawBrowserMessageEnvelop.Deserialize(br);
        //            BrowserMessageHandler.Handle(app, envelop);
        //        }
        //    }
        //    finally
        //    {
        //        try
        //        {
        //            NamedPipeClientStream? op = null;
        //            lock (this)
        //            {
        //                if (inOutMap.TryGetValue(inPipe, out op))
        //                {
        //                    inOutMap.Remove(inPipe);
        //                }
        //            }
        //            op?.Close();
        //            op?.Dispose();
        //        }
        //        catch { }
        //    }
        //}

        //private void SendConfig(Stream pipe)
        //{
        //    var bytes = GetSyncBytes(app);
        //    NativeMessageSerializer.WriteMessage(pipe, bytes);
        //}

        //private static void ReadFully(Stream stream, byte[] buf, int bytesToRead)
        //{
        //    var rem = bytesToRead;
        //    var index = 0;
        //    while (rem > 0)
        //    {
        //        var c = stream.Read(buf, index, rem);
        //        if (c == 0) throw new IOException("Unexpected EOF");
        //        index += c;
        //        rem -= c;
        //    }
        //}

        //private static byte[] ReadMessageBytes(Stream pipe)
        //{
        //    var b4 = new byte[4];
        //    ReadFully(pipe, b4, 4);
        //    var syncLength = BitConverter.ToInt32(b4, 0);
        //    if (syncLength > 4 * 8196)
        //    {
        //        throw new ArgumentException($"Message length too long: {syncLength}");
        //    }
        //    var bytes = new byte[syncLength];
        //    ReadFully(pipe, bytes, syncLength);
        //    return bytes;
        //}

        //private static void WriteMessage(Stream pipe, string message)
        //{
        //    var msgBytes = Encoding.UTF8.GetBytes(message);
        //    WriteMessage(pipe, msgBytes);
        //}

        //private static void WriteMessage(Stream pipe, byte[] msgBytes)
        //{

        //    var bytes = BitConverter.GetBytes(msgBytes.Length);
        //    pipe.Write(bytes, 0, bytes.Length);
        //    pipe.Write(msgBytes, 0, msgBytes.Length);
        //    pipe.Flush();
        //}

        public void Dispose()
        {
            lock (connectedChannels)
            {
                foreach (var channel in connectedChannels)
                {
                    channel.Disconnect();
                }
            }
            //foreach (var pipe in inPipes)
            //{
            //    try { pipe.Disconnect(); } catch { }
            //    try { pipe.Dispose(); } catch { }
            //}
        }

        private static byte[] GetSyncBytes(IApp app)
        {
            var msg = new SyncMessage()
            {
                Enabled = Config.Instance.IsBrowserMonitoringEnabled,
                BlockedHosts = Config.Instance.BlockedHosts,
                VideoUrls = new string[0],
                FileExts = Config.Instance.FileExtensions,
                VidExts = Config.Instance.VideoExtensions,
                VidList = app.GetVideoList(false).Select(a => new VideoItem
                {
                    Id = a.ID,
                    Text = a.File,
                    Info = a.DisplayName
                }).ToList(),
                MimeList = new string[] { "video", "audio", "mpegurl", "f4m", "m3u8", "dash" },
                BlockedMimeList = new string[] { "text/javascript", "application/javascript", "text/css", "text/html" },
                VideoUrlsWithPostReq = new string[] { "ubei/v1/player?key=", "ubei/v1/next?key=" }
            };
            return msg.Serialize();
        }

        private static void SendArgsToRunningInstance(string[] args)
        {
            if (args == null || args.Length < 1) return;
            try
            {
                using var npc =
                               new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                npc.Connect();
                var b = new MemoryStream();
                var wb = new BinaryWriter(b);
                wb.Write(Int32.MaxValue);
                wb.Write(string.Join("\r", args));
                wb.Close();
                NativeMessageSerializer.WriteMessage(npc, b.ToArray());
                npc.Flush();
                npc.Close();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }
    }

    public class InstanceAlreadyRunningException : Exception
    {
        public InstanceAlreadyRunningException(string message) : base(message)
        {
        }
    }
}
