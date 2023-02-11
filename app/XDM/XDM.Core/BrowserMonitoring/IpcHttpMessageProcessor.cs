using System;
using System.Text;
using System.Net;
using System.Linq;
using Newtonsoft.Json;
using XDM.Core.Util;
using XDM.Core.HttpServer;
using System.Threading;
using TraceLog;
using Translations;
using System.IO;
using System.Collections.Generic;

namespace XDM.Core.BrowserMonitoring
{
    public class IpcHttpMessageProcessor
    {
        private NanoServer server;
        private static string[] blockedHeaders = { "accept", "if", "authorization", "proxy", "connection", "expect", "TE",
            "upgrade", "range", "cookie", "transfer-encoding", "content-type", "content-length","content-encoding" };

        public IpcHttpMessageProcessor()
        {
            server = new NanoServer(IPAddress.Loopback, 8597);
            server.RequestReceived += (sender, args) =>
            {
                HandleRequest(args.RequestContext);
            };
        }

        public void Run()
        {
            new Thread(() =>
            {
                try
                {
                    server.Start();
                }
                catch (Exception ex)
                {
                    Log.Debug(ex.ToString());
                    ApplicationContext.Application.ShowMessageBox(null, TextResource.GetText("MSG_ALREADY_RUNNING"));
                }
            }).Start();
        }

        public void HandleRequest(RequestContext context)
        {
            try
            {
                switch (context.RequestPath)
                {
                    case "/sync":
                        break;
                    case "/download":
                        OnDownloadMessage(context);
                        break;
                    case "/media":
                        OnMediaMessage(context);
                        break;
                    case "/tab-update":
                        OnTabUpdateMessage(context);
                        break;
                    case "/vid":
                        OnVideoDownloadMessage(context);
                        break;
                    case "/clear":
                        ApplicationContext.VideoTracker.ClearVideoList();
                        break;
                    case "/link":
                        OnBatchMessage(context);
                        break;
                    case "/args":
                        OnArgsMessage(context);
                        break;
                    default:
                        throw new ArgumentException("Unsupported request: " + context.RequestPath);
                }
                OnSyncMessage(context);
            }
            catch (Exception ex)
            {
                Log.Debug(ex.ToString());
                throw;
            }
        }

        private void OnArgsMessage(RequestContext context)
        {
            var args = JsonConvert.DeserializeObject<List<string>>(Encoding.UTF8.GetString(context.RequestBody!));
            if (args == null || args.Count == 0)
            {
                return;
            }
            ArgsProcessor.Process(args);
        }

        private void OnVideoDownloadMessage(RequestContext context)
        {
            var msg = JsonConvert.DeserializeObject<ExtensionData>(Encoding.UTF8.GetString(context.RequestBody!));
            if (msg == null)
            {
                return;
            }
            ApplicationContext.VideoTracker.AddVideoDownload(msg.Vid);
        }

        private void OnTabUpdateMessage(RequestContext context)
        {
            var msg = JsonConvert.DeserializeObject<ExtensionData>(Encoding.UTF8.GetString(context.RequestBody!));
            if (msg == null)
            {
                return;
            }
            ApplicationContext.VideoTracker.UpdateMediaTitle(msg.TabUrl, msg.TabTitle);
        }

        private void OnDownloadMessage(RequestContext context)
        {
            var msg = JsonConvert.DeserializeObject<ExtensionData>(Encoding.UTF8.GetString(context.RequestBody!));
            if (msg == null)
            {
                return;
            }
            var dmsg = new Message();
            dmsg.Url = msg.Url;
            dmsg.RequestMethod = msg.Method;
            dmsg.RequestHeaders = msg.RequestHeaders;
            dmsg.ResponseHeaders = msg.ResponseHeaders;
            dmsg.Cookies = msg.Cookie;
            dmsg.File = FileHelper.SanitizeFileName(msg.File)!;
            dmsg.TabUrl = msg.TabUrl;
            dmsg.TabId = msg.TabId;
            RemoveBlockedHeaders(dmsg);
            ApplicationContext.CoreService.AddDownload(dmsg);
        }

        private void OnMediaMessage(RequestContext context)
        {
            var msg = JsonConvert.DeserializeObject<ExtensionData>(Encoding.UTF8.GetString(context.RequestBody!));
            if (msg == null)
            {
                return;
            }
            var dmsg = new Message();
            dmsg.Url = msg.Url;
            dmsg.RequestMethod = msg.Method;
            dmsg.RequestHeaders = msg.RequestHeaders;
            dmsg.ResponseHeaders = msg.ResponseHeaders;
            dmsg.Cookies = msg.Cookie;
            dmsg.File = FileHelper.SanitizeFileName(msg.File)!;
            dmsg.TabUrl = msg.TabUrl;
            dmsg.TabId = msg.TabId;
            RemoveBlockedHeaders(dmsg);
            VideoUrlHelper.ProcessMediaMessage(dmsg);
        }

        private void OnBatchMessage(RequestContext context)
        {
            var msgArr = JsonConvert.DeserializeObject<ExtensionData[]>(Encoding.UTF8.GetString(context.RequestBody!));
            if (msgArr == null)
            {
                return;
            }
            ApplicationContext.CoreService.AddBatchLinks(msgArr.Select(msg =>
            {
                var dmsg = new Message();
                dmsg.Url = msg.Url;
                dmsg.RequestMethod = msg.Method;
                dmsg.RequestHeaders = msg.RequestHeaders;
                dmsg.ResponseHeaders = msg.ResponseHeaders;
                dmsg.Cookies = msg.Cookie;
                dmsg.File = FileHelper.SanitizeFileName(msg.File)!;
                dmsg.TabUrl = msg.TabUrl;
                dmsg.TabId = msg.TabId;
                RemoveBlockedHeaders(dmsg);
                return dmsg;
            }).ToList());
        }

        //public void HandleRequest2(RequestContext context)
        //{
        //    if (context.RequestPath == "/204")
        //    {
        //        context.ResponseStatus = new ResponseStatus
        //        {
        //            StatusCode = 204,
        //            StatusMessage = "No Content"
        //        };
        //        context.AddResponseHeader("Cache-Control", "max-age=0, no-cache, must-revalidate");
        //        context.SendResponse();
        //        return;
        //    }

        //    try
        //    {
        //        switch (context.RequestPath)
        //        {
        //            case "/download":
        //                {
        //                    var text = Encoding.UTF8.GetString(context.RequestBody!);
        //                    Log.Debug(text);
        //                    var message = Message.ParseMessage(text);
        //                    if (!(Helpers.IsBlockedHost(message.Url) || Helpers.IsCompressedJSorCSS(message.Url)))
        //                    {
        //                        ApplicationContext.CoreService.AddDownload(message);
        //                    }
        //                    break;
        //                }
        //            case "/video":
        //                {
        //                    var text = Encoding.UTF8.GetString(context.RequestBody!);
        //                    Log.Debug(text);
        //                    var message2 = Message.ParseMessage(Encoding.UTF8.GetString(context.RequestBody!));
        //                    var contentType = message2.GetResponseHeaderFirstValue("Content-Type")?.ToLowerInvariant() ?? string.Empty;
        //                    if (VideoUrlHelper.IsHLS(contentType))
        //                    {
        //                        VideoUrlHelper.ProcessHLSVideo(message2);
        //                    }
        //                    if (VideoUrlHelper.IsDASH(contentType))
        //                    {
        //                        VideoUrlHelper.ProcessDashVideo(message2);
        //                    }
        //                    if (!VideoUrlHelper.ProcessYtDashSegment(message2))
        //                    {
        //                        if (contentType != null && !(contentType.Contains("f4f") ||
        //                            contentType.Contains("m4s") ||
        //                            contentType.Contains("mp2t") || message2.Url.Contains("abst") ||
        //                            message2.Url.Contains("f4x") || message2.Url.Contains(".fbcdn")
        //                            || message2.Url.Contains("http://127.0.0.1:9614")))
        //                        {
        //                            VideoUrlHelper.ProcessNormalVideo(message2);
        //                        }
        //                    }
        //                    break;
        //                }
        //            case "/links":
        //                {
        //                    var text = Encoding.UTF8.GetString(context.RequestBody!);
        //                    Log.Debug(text);
        //                    var arr = text.Split(new string[] { "\r\n\r\n" }, StringSplitOptions.RemoveEmptyEntries);
        //                    ApplicationContext.CoreService.AddBatchLinks(arr.Select(str => Message.ParseMessage(str.Trim())).ToList());
        //                    break;
        //                }
        //            case "/item":
        //                {
        //                    foreach (var item in Encoding.UTF8.GetString(context.RequestBody!).Split(new char[] { '\r', '\n' }))
        //                    {
        //                        ApplicationContext.VideoTracker.AddVideoDownload(item);
        //                    }
        //                    break;
        //                }
        //            case "/clear":
        //                ApplicationContext.VideoTracker.ClearVideoList();
        //                break;
        //        }
        //    }
        //    finally
        //    {
        //        SendSyncResponse(context);
        //    }
        //}

        private void OnSyncMessage(RequestContext context)
        {
            Log.Debug("Sync...");
            var json = CreateConfigJson();
            context.ResponseStatus = new ResponseStatus
            {
                StatusCode = 200,
                StatusMessage = "OK"
            };
            context.AddResponseHeader("Content-Type", "application/json");
            context.AddResponseHeader("Cache-Control", "max-age=0, no-cache, must-revalidate");
            context.ResponseBody = Encoding.UTF8.GetBytes(json);
            context.SendResponse();
        }

        private string? CreateConfigJson()
        {
            try
            {
                Log.Debug("Creating config JSON");
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

                    writer.WritePropertyName("tabId");
                    writer.WriteValue(video.TabId);

                    writer.WriteEndObject();
                }
                writer.WriteEndArray();

                writer.WritePropertyName("matchingHosts");
                writer.WriteStartArray();
                foreach (var ext in new string[] { "googlevideo" })
                {
                    writer.WriteValue(ext);
                }
                writer.WriteEndArray();

                writer.WriteEndObject();
                writer.Close();
                var str = w.ToString();
                return str;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error sending config");
                return null;
            }
        }

        private void RemoveBlockedHeaders(Message message)
        {
            foreach (var header in blockedHeaders)
            {
                string? keyName = null;
                foreach (var key in message.RequestHeaders.Keys)
                {
                    if (key.Equals(header, StringComparison.InvariantCultureIgnoreCase))
                    {
                        keyName = key;
                        break;
                    }
                }
                if (!String.IsNullOrEmpty(keyName))
                {
                    message.RequestHeaders.Remove(keyName!);
                }
            }
        }
    }
}
