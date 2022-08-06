using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.IO.Pipes;
using System.IO;
using XDM.Core;
using System.Threading;
#if NET35
using XDM.Compatibility;
#else
using System.Collections.Concurrent;
#endif
using TraceLog;

namespace XDM.Core.BrowserMonitoring
{
    public class IpcPipe : IDisposable
    {
        private int MaxPipeInstance = 254;
        private static readonly string PipeName = "XDM_Ipc_Browser_Monitoring_Pipe";
        private List<PipeChannel> connectedChannels = new();
        private Thread listenerThread;

        public static void EnsureSingleInstance()
        {

        }

        public IpcPipe()
        {
            EnsureSingleInstance();
        }

        public void BroadcastConfig()
        {
            var bytes = GetSyncBytes();
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
        }

        public void Run()
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
                          channel.Start(GetSyncBytes());
                      }
                  }
              });
            listenerThread.Start();
            ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;
        }

        private void ApplicationContext_ApplicationEvent(object? sender, ApplicationEvent e)
        {
            if (e.EventType == "ConfigChanged")
            {
                BroadcastConfig();
            }
        }

        private PipeChannel CreateChannel(NamedPipeServerStream pipe)
        {
            var channel = new PipeChannel(pipe);
            channel.MessageReceived += (sender, args) =>
            {
                try
                {
                    using var br = new BinaryReader(new MemoryStream(args.Data));
                    var envelop = RawBrowserMessageEnvelop.Deserialize(br);
                    IpcPipeMessageProcessor.Process(envelop);
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
                    connectedChannels.Remove((PipeChannel)sender);
                }
            };
            return channel;
        }

        public void Dispose()
        {
            lock (connectedChannels)
            {
                foreach (var channel in connectedChannels)
                {
                    channel.Disconnect();
                }
            }
        }

        private static byte[] GetSyncBytes()
        {
            var msg = new SyncMessage()
            {
                Enabled = Config.Instance.IsBrowserMonitoringEnabled,
                BlockedHosts = Config.Instance.BlockedHosts,
                VideoUrls = new string[0],
                FileExts = Config.Instance.FileExtensions,
                VidExts = Config.Instance.VideoExtensions,
                VidList = ApplicationContext.VideoTracker.GetVideoList(false).Select(a => new VideoItem
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

        public static void SendArgsToRunningInstance(IEnumerable<string> args)
        {
            var arguments = args;
            if (arguments == null)
            {
                arguments = new string[0];
            }
            try
            {
                using var npc = new NamedPipeClientStream(".", PipeName, PipeDirection.Out);
                npc.Connect();
                var b = new MemoryStream();
                var wb = new BinaryWriter(b);
                wb.Write(Int32.MaxValue);
                wb.Write(string.Join("\r", arguments.ToArray()));
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
}
