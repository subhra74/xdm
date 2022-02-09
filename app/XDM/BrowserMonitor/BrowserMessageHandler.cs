using System;
using System.Collections.Generic;
using System.Linq;
using TraceLog;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Util;

namespace BrowserMonitoring
{
    internal static class BrowserMessageHandler
    {
        internal static void Handle(IApp app, RawBrowserMessageEnvelop envelop)
        {
            //Log.Debug("Type: " + envelop.MessageType);
            if (envelop.MessageType == "videoIds")
            {
                foreach (var item in envelop.VideoIds)
                {
                    app.AddVideoDownload(item);
                }
                return;
            }

            if (envelop.MessageType == "clear")
            {
                app.ClearVideoList();
                return;
            }

            if (envelop.MessageType == "sync")
            {
                return;
            }

            var rawMessage = envelop.Message;
            if (rawMessage == null && envelop.Messages == null)
            {
                Log.Debug("Raw message/messages is null");
                return;
            };

            switch (envelop.MessageType)
            {
                case "download":
                    {
                        var message = Parse(rawMessage);
                        if (!(Helpers.IsBlockedHost(message.Url) || Helpers.IsCompressedJSorCSS(message.Url)))
                        {
                            app.AddDownload(message);
                        }
                        break;
                    }
                case "links":
                    {
                        var messages = new List<Message>(envelop.Messages.Length);
                        foreach (var msg in envelop.Messages)
                        {
                            var message = Parse(msg);
                            messages.Add(message);
                        }
                        app.AddBatchLinks(messages);
                        break;
                    }
                case "video":
                    {
                        var message = Parse(rawMessage);
                        var contentType = message.GetResponseHeaderFirstValue("Content-Type");

                        if (VideoUrlHelper.IsYtFormat(contentType))
                        {
                            VideoUrlHelper.ProcessPostYtFormats(message, app);
                        }

                        //if (VideoUrlHelper.IsFBFormat(contentType, message.Url))
                        //{
                        //    VideoUrlHelper.ProcessPostFBFormats(message, app);
                        //}

                        if (VideoUrlHelper.IsHLS(contentType))
                        {
                            VideoUrlHelper.ProcessHLSVideo(message, app);
                        }

                        if (VideoUrlHelper.IsDASH(contentType))
                        {
                            VideoUrlHelper.ProcessDashVideo(message, app);
                        }

                        if (!VideoUrlHelper.ProcessYtDashSegment(message, app))
                        {
                            if (VideoUrlHelper.IsNormalVideo(contentType, message.Url, message.GetContentLength()))
                            {
                                VideoUrlHelper.ProcessNormalVideo(message, app);
                            }
                        }
                        break;
                    }
            }
        }

        private static string GetFileName(string text)
        {
            try
            {
                return Uri.UnescapeDataString(text);
            }
            catch { }
            return text;
        }

        internal static Message Parse(RawBrowserMessage rawMessage)
        {
            var message = new Message
            {
                File = GetFileName(rawMessage.File),
                Url = rawMessage.Url,
                RequestMethod = rawMessage.Method ?? "GET",
                RequestBody = rawMessage.RequestBody
            };

            var cookies = new List<string>();

            if (rawMessage.RequestHeaders != null && rawMessage.RequestHeaders.Count > 0)
            {
                foreach (var key in rawMessage.RequestHeaders.Keys)
                {
                    if (string.IsNullOrEmpty(key)) continue;
                    if (key.Equals("cookie", StringComparison.InvariantCultureIgnoreCase))
                    {
                        cookies.AddRange(rawMessage.RequestHeaders[key]);
                    }
                    var invalidHeader = IsBlockedHeader(key);
                    if (key.Equals("content-type", StringComparison.InvariantCultureIgnoreCase) &&
                        !string.IsNullOrEmpty(rawMessage.RequestBody))
                    {
                        invalidHeader = false;
                    }
                    if (!invalidHeader)
                    {
                        message.RequestHeaders.Add(key, rawMessage.RequestHeaders[key]);
                    }
                }
            }

            var cookieSet = new HashSet<string>();
            foreach (var cookie in cookies)
            {
                foreach (var item in cookie.Split(';'))
                {
                    var value = item.Trim();
                    if (!string.IsNullOrEmpty(value))
                    {
                        cookieSet.Add(value);
                    }
                }
            }

            if (rawMessage.ResponseHeaders != null && rawMessage.ResponseHeaders.Count > 0)
            {
                foreach (var key in rawMessage.ResponseHeaders.Keys)
                {
                    if (!string.IsNullOrEmpty(key))
                    {
                        message.ResponseHeaders.Add(key, rawMessage.ResponseHeaders[key]);
                    }
                }
            }

            if (rawMessage.Cookies != null && rawMessage.Cookies.Count > 0)
            {
                foreach (var key in rawMessage.Cookies.Keys)
                {
                    if (!string.IsNullOrEmpty(key))
                    {
                        cookieSet.Add(key + "=" + rawMessage.Cookies[key]);
                    }
                }
            }

            if (cookieSet.Count > 0)
            {
                message.Cookies.Add("Cookie", Helpers.MakeCookieString(cookieSet));
            }

            if (!message.RequestHeaders.ContainsKey("User-Agent") && message.ResponseHeaders.ContainsKey("realUA"))
            {
                message.ResponseHeaders["User-Agent"] = message.ResponseHeaders["realUA"];
            }

            return message;
        }

        private static bool IsBlockedHeader(string header) =>
            blockedHeaders.Any(blockedHeader => (header?.ToLowerInvariant() ?? string.Empty).StartsWith(blockedHeader));

        private static string[] blockedHeaders = { "accept", "if", "authorization", "proxy", "connection", "expect", "te",
            "upgrade", "range", "transfer-encoding", "content-type", "content-length","content-encoding" ,"accept-encoding"};

    }
}
