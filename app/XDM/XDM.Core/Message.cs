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
        public Dictionary<string, string> Cookies { get; set; }
        public string RequestMethod { get; set; }
        public string RequestBody { get; set; }

        public Message()
        {
            this.RequestHeaders = new Dictionary<string, List<string>>();
            this.ResponseHeaders = new Dictionary<string, List<string>>();
            this.Cookies = new Dictionary<string, string>();
            this.RequestMethod = "GET";
        }

        public static Message ParseMessage(string text)
        {
            var message = new Message();
            foreach (var line in text.Split('\r', '\n'))
            {
                if (line == null) break;
                (string key, string val, _) = ParsingHelper.ParseKeyValuePair(line, '=');
                switch (key.ToLowerInvariant())
                {
                    case "file":
                        message.File = FileHelper.SanitizeFileName(val);
                        break;
                    case "url":
                        message.Url = val;
                        break;
                    case "req":
                    case "res":
                    case "cookie":
                        (string k1, string v1, _) = ParsingHelper.ParseKeyValuePair(val, ':');
                        if (!string.IsNullOrEmpty(k1))
                        {
                            switch (key)
                            {
                                case "req":
                                    var reqHeaderValues = message.RequestHeaders.GetValueOrDefault(k1, new List<string>());
                                    var keyItem = k1.ToLowerInvariant();
                                    if (IsBlockedHeader(keyItem))
                                    {
                                        continue;
                                    }
                                    reqHeaderValues.Add(v1);
                                    message.RequestHeaders[k1] = reqHeaderValues;
                                    break;
                                case "res":
                                    var resHeaderValues = message.ResponseHeaders.GetValueOrDefault(k1, new List<string>());
                                    resHeaderValues.Add(v1);
                                    message.ResponseHeaders[k1] = resHeaderValues;
                                    break;
                                case "cookie":
                                    message.Cookies.Add(k1, v1);
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
