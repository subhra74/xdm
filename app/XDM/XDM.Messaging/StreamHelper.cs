using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace XDM.Messaging
{
    public static class StreamHelper
    {
        public static string? ReadString(BinaryReader r)
        {
            var str = r.ReadString();
            if (!string.IsNullOrEmpty(str))
            {
                return str;
            }
            return null;
        }

        public static void WriteStateHeaders(Dictionary<string, List<string>>? headers, BinaryWriter w)
        {
            w.Write(headers == null ? 0 : headers.Count);
            if (headers != null && headers.Count > 0)
            {
                foreach (var key in headers.Keys)
                {
                    w.Write(key);
                    var list = headers[key];
                    w.Write(list.Count);
                    foreach (var item in list)
                    {
                        w.Write(item);
                    }
                }
            }
        }

        public static void ReadStateHeaders(BinaryReader r, out Dictionary<string, List<string>> headers)
        {
            headers = new Dictionary<string, List<string>>();
            var count = r.ReadInt32();
            for (var i = 0; i < count; i++)
            {
                var key = r.ReadString();
                var c = r.ReadInt32();
                var list = new List<string>(c);
                for (var k = 0; k < c; k++)
                {
                    list.Add(r.ReadString());
                }
                headers[key] = list;
            }
        }

        public static void WriteStateCookies(Dictionary<string, string>? cookies, BinaryWriter w)
        {
            w.Write(cookies == null ? 0 : cookies.Count);
            if (cookies != null && cookies.Count > 0)
            {
                foreach (var key in cookies.Keys)
                {
                    w.Write(key);
                    w.Write(cookies[key]);
                }
            }
        }

        public static void ReadStateCookies(BinaryReader r, out Dictionary<string, string> cookies)
        {
            cookies = new Dictionary<string, string>();
            var count = r.ReadInt32();
            for (var i = 0; i < count; i++)
            {
                cookies[r.ReadString()] = r.ReadString();
            }
        }
    }
}
