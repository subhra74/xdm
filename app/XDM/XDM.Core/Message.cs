using System;
using System.Collections.Generic;
using XDM.Core.Util;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core
{
    public class Message
    {
        public string File { get; set; }
        public string Url { get; set; }
        public Dictionary<string, List<string>> RequestHeaders { get; set; }
        public Dictionary<string, List<string>> ResponseHeaders { get; set; }
        public string? Cookies { get; set; }
        public string RequestMethod { get; set; }
        public string RequestBody { get; set; }

        public Message()
        {
            this.RequestHeaders = new Dictionary<string, List<string>>();
            this.ResponseHeaders = new Dictionary<string, List<string>>();
            this.RequestMethod = "GET";
        }

        public static Message ParseMessage(string text)
        {
            var message = new Message();
            foreach (var line in text.Split('\r', '\n'))
            {
                if (line == null) break;
                var kv = ParsingHelper.ParseKeyValuePair(line, '=');
                switch (kv.Key.ToLowerInvariant())
                {
                    case "file":
                        message.File = FileHelper.SanitizeFileName(kv.Value);
                        break;
                    case "url":
                        message.Url = kv.Value;
                        break;
                    case "req":
                    case "res":
                    case "cookie":
                        var kv1 = ParsingHelper.ParseKeyValuePair(kv.Value, ':');
                        if (!string.IsNullOrEmpty(kv1.Key))
                        {
                            switch (kv.Key)
                            {
                                case "req":
                                    var reqHeaderValues = message.RequestHeaders.GetValueOrDefault(kv1.Key, new List<string>());
                                    var keyItem = kv1.Key.ToLowerInvariant();
                                    if (IsBlockedHeader(keyItem))
                                    {
                                        continue;
                                    }
                                    reqHeaderValues.Add(kv1.Value);
                                    message.RequestHeaders[kv1.Key] = reqHeaderValues;
                                    break;
                                case "res":
                                    var resHeaderValues = message.ResponseHeaders.GetValueOrDefault(kv1.Key, new List<string>());
                                    resHeaderValues.Add(kv1.Value);
                                    message.ResponseHeaders[kv1.Key] = resHeaderValues;
                                    break;
                                case "cookie":
                                    message.Cookies = kv1.Value;
                                    break;
                            }
                        }
                        break;
                }
            }

            if (!message.RequestHeaders.ContainsKey("User-Agent") && message.ResponseHeaders.ContainsKey("realUA"))
            {
                message.ResponseHeaders["User-Agent"] = message.ResponseHeaders["realUA"];
            }
            return message;
        }

        private static List<string> GetHeaderValue(Dictionary<string, List<string>> headers, string key)
        {
            if (headers.ContainsKey(key))
            {
                return headers[key];
            }
            key = key.ToLowerInvariant();
            if (headers.ContainsKey(key))
            {
                return headers[key];
            }
            key = key.ToLowerInvariant();
            if (headers.ContainsKey(key))
            {
                return headers[key];
            }
            return null;
        }

        public List<string> GetRequestHeaderValue(string key)
        {
            return GetHeaderValue(RequestHeaders, key);
        }

        public List<string> GetResponseHeaderValue(string key)
        {
            return GetHeaderValue(ResponseHeaders, key);
        }

        public string GetRequestHeaderFirstValue(string key)
        {
            var headers = GetHeaderValue(RequestHeaders, key);
            if (headers != null && headers.Count > 0) return headers[0];
            return null;
        }

        public string GetResponseHeaderFirstValue(string key)
        {
            var headers = GetHeaderValue(ResponseHeaders, key);
            if (headers != null && headers.Count > 0) return headers[0];
            return null;
        }

        public long GetContentLength()
        {
            try
            {
                var value = GetResponseHeaderFirstValue("Content-Length");
                if (value != null) return Int32.Parse(value);
            }
            catch { }
            return 0;
        }

        private static bool IsBlockedHeader(string header)
        {
            if (!string.IsNullOrEmpty(header))
            {
                foreach (var bh in blockedHeaders)
                {
                    if (header.StartsWith(bh))
                    {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }

        private static string[] blockedHeaders = { "accept", "if", "authorization", "proxy", "connection", "expect", "TE",
            "upgrade", "range", "cookie", "transfer-encoding", "content-type", "content-length","content-encoding" };
    }
}
