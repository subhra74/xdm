using System;
using System.Text;
using System.Net;
using System.Linq;
using Newtonsoft.Json;
using XDM.Core;
using XDM.Core.Util;
using XDM.Core.HttpServer;
using System.Threading;
using TraceLog;
using Translations;

namespace XDM.Core.BrowserMonitoring
{
    public class IpcHttpHandler
    {
        private IAppService app;
        private NanoServer server;

        public IpcHttpHandler(IAppService app)
        {
            this.app = app;
            server = new NanoServer(IPAddress.Loopback, 9614);
            server.RequestReceived += (sender, args) =>
            {
                HandleRequest(args.RequestContext);
            };
        }

        public void StartHttpIpcChannel()
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
                    app.AppUI.ShowMessageBox(null, TextResource.GetText("MSG_ALREADY_RUNNING"));
                }
            }).Start();
        }

        public void HandleRequest(RequestContext context)
        {
            if (context.RequestPath == "/204")
            {
                context.ResponseStatus = new ResponseStatus
                {
                    StatusCode = 204,
                    StatusMessage = "No Content"
                };
                context.AddResponseHeader("Cache-Control", "max-age=0, no-cache, must-revalidate");
                context.SendResponse();
                return;
            }

            try
            {
                switch (context.RequestPath)
                {
                    case "/download":
                        {
                            var text = Encoding.UTF8.GetString(context.RequestBody!);
                            Log.Debug(text);
                            var message = Message.ParseMessage(text);
                            if (!(Helpers.IsBlockedHost(message.Url) || Helpers.IsCompressedJSorCSS(message.Url)))
                            {
                                app.AddDownload(message);
                            }
                            break;
                        }
                    case "/video":
                        {
                            var text = Encoding.UTF8.GetString(context.RequestBody!);
                            Log.Debug(text);
                            var message2 = Message.ParseMessage(Encoding.UTF8.GetString(context.RequestBody!));
                            var contentType = message2.GetResponseHeaderFirstValue("Content-Type")?.ToLowerInvariant() ?? string.Empty;
                            if (VideoUrlHelper.IsHLS(contentType))
                            {
                                VideoUrlHelper.ProcessHLSVideo(message2, app);
                            }
                            if (VideoUrlHelper.IsDASH(contentType))
                            {
                                VideoUrlHelper.ProcessDashVideo(message2, app);
                            }
                            if (!VideoUrlHelper.ProcessYtDashSegment(message2, app))
                            {
                                if (contentType != null && !(contentType.Contains("f4f") ||
                                    contentType.Contains("m4s") ||
                                    contentType.Contains("mp2t") || message2.Url.Contains("abst") ||
                                    message2.Url.Contains("f4x") || message2.Url.Contains(".fbcdn")
                                    || message2.Url.Contains("http://127.0.0.1:9614")))
                                {
                                    VideoUrlHelper.ProcessNormalVideo(message2, app);
                                }
                            }
                            break;
                        }
                    case "/links":
                        {
                            var text = Encoding.UTF8.GetString(context.RequestBody!);
                            Log.Debug(text);
                            var arr = text.Split(new string[] { "\r\n\r\n" }, StringSplitOptions.RemoveEmptyEntries);
                            app.AddBatchLinks(arr.Select(str => Message.ParseMessage(str.Trim())).ToList());
                            break;
                        }
                    case "/item":
                        {
                            foreach (var item in Encoding.UTF8.GetString(context.RequestBody!).Split(new char[] { '\r', '\n' }))
                            {
                                app.AddVideoDownload(item);
                            }
                            break;
                        }
                    case "/clear":
                        app.ClearVideoList();
                        break;
                }
            }
            finally
            {
                SendSyncResponse(context);
            }
        }

        private void SendSyncResponse(RequestContext context)
        {
            var resp = new
            {
                enabled = Config.Instance.IsBrowserMonitoringEnabled,
                blockedHosts = new string[0],
                videoUrls = new string[0],
                fileExts = Config.Instance.FileExtensions,
                vidExts = Config.Instance.VideoExtensions,
                vidList = app.GetVideoList().Select(a => new
                {
                    id = a.ID,
                    text = a.File,
                    info = a.DisplayName
                }).ToList(),
                mimeList = new string[] { "video", "audio", "mpegurl", "f4m", "m3u8", "dash" }
            };
            var json = JsonConvert.SerializeObject(resp);
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
    }
}
