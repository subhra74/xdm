using NativeMessaging;
using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using TraceLog;
using XDM.Core.Util;

namespace XDM.Core.Ipc
{
    public sealed class IpcServer
    {
        private TcpListener? _listener;
        private HashSet<TcpClient> _clients = new();
        private int _port;

        public event EventHandler<IpcPacketReceivedEventArgs>? PacketReceived;

        public IpcServer(int port)
        {
            this._port = port;
        }

        public void Start()
        {
            ApplicationContext.VideoTracker.MediaAdded += VideoTracker_MediaAdded;
            ApplicationContext.VideoTracker.MediaUpdated += VideoTracker_MediaUpdated;
            this._listener = new TcpListener(IPAddress.Loopback, _port);
            this._listener.Start();
            new Thread(() =>
            {
                while (true)
                {
                    var tcp = this._listener.AcceptTcpClient();
                    this._clients.Add(tcp);
                    ProcessRequest(tcp);
                }
            }).Start();
        }

        private void VideoTracker_MediaUpdated(object sender, BrowserMonitoring.MediaInfoEventArgs e)
        {
            SendConfig();
        }

        private void VideoTracker_MediaAdded(object sender, BrowserMonitoring.MediaInfoEventArgs e)
        {
            SendConfig();
        }

        private void ProcessRequest(TcpClient tcp)
        {
            new Thread(() =>
            {
                try
                {
                    var stream = tcp.GetStream();
                    SendConfig(tcp);
                    try
                    {
                        ApplicationContext.ExtensionRegistered();
                    }
                    catch(Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                    while (true)
                    {
                        Log.Debug("Receiving from native-host");
                        var args = IpcUtil.Receive(stream);
                        Log.Debug("Received from native-host");
                        if (!args.Contains("--restore-window"))
                        {
                            args.Add("--background");
                        }
                        Log.Debug(string.Join(",", args));
                        ArgsProcessor.Process(args);
                    }
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, ex.Message);
                }
                finally
                {
                    try
                    {
                        lock (this)
                        {
                            this._clients.Remove(tcp);
                        }
                        tcp.Close();
                    }
                    catch { }
                }
            }).Start();
        }

        public void SendConfig()
        {
            try
            {
                lock (this)
                {
                    foreach (var client in _clients)
                    {
                        SendConfig(client);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private void SendConfig(TcpClient client)
        {
            try
            {
                Log.Debug("Sending config");
                var w = new StringWriter();
                using var writer = new JsonTextWriter(w);
                writer.CloseOutput = false;
                writer.Formatting = Formatting.None;

                writer.WriteStartObject();

                writer.WritePropertyName("enabled");
                writer.WriteValue(Config.Instance.IsBrowserMonitoringEnabled);

                writer.WritePropertyName("fileExts");
                writer.WriteStartArray();
                foreach (var ext in Config.Instance.FileExtensions)
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                writer.WritePropertyName("blockedHosts");
                writer.WriteStartArray();
                foreach (var host in Config.Instance.BlockedHosts)
                {
                    writer.WriteValue(host);
                }
                writer.WriteEndArray();

                writer.WritePropertyName("requestFileExts");
                writer.WriteStartArray();
                foreach (var ext in Config.Instance.VideoExtensions)
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                writer.WritePropertyName("mediaTypes");
                writer.WriteStartArray();
                foreach (var ext in new string[] { "audio/", "video/" })
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                writer.WritePropertyName("tabsWatcher");
                writer.WriteStartArray();
                foreach (var ext in new string[] { ".youtube.", "/watch?v=" })
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                var videoList = ApplicationContext.VideoTracker.GetVideoList();

                writer.WritePropertyName("videoList");
                writer.WriteStartArray();
                foreach (var video in videoList)
                {
                    writer.WriteStartObject();

                    writer.WritePropertyName("id");
                    writer.WriteValue(video.ID);

                    writer.WritePropertyName("text");
                    writer.WriteValue(video.Name);

                    writer.WritePropertyName("info");
                    writer.WriteValue(video.Description);

                    writer.WriteEndObject();
                }
                writer.WriteEndArray();

                writer.WritePropertyName("matchingHosts");
                writer.WriteStartArray();
                foreach (var ext in new string[] { "googlevideo"})
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                writer.WriteEndObject();
                writer.Close();
                var str = w.ToString();

                IpcUtil.Send(client.GetStream(), new string[] { str });

                Log.Debug("Config sent");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error sending config");
            }
        }

        public void Stop()
        {
            try
            {
                this._listener?.Stop();
            }
            catch { }
        }
    }

    public struct IpcPacket
    {
        public string Header { get; set; }
        public List<string> Data { get; set; }
    }

    public class IpcPacketReceivedEventArgs : EventArgs
    {
        public IpcPacket Packet { get; }
        public IpcPacketReceivedEventArgs(IpcPacket context)
        {
            this.Packet = context;
        }
    }
}
