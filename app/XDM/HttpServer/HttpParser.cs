using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Sockets;
using TraceLog;
#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

namespace HttpServer
{
    internal static class HttpParser
    {
        public static string ParseRequestStatusLine(string statusLine)
        {
            try
            {
                var arr = statusLine.Split(' ');
                if (arr.Length > 2)
                {
                    return arr[0];
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            throw new IOException($"Invalid HTTP status line: {statusLine}");
        }

        internal static void ParseHeader(string headerLine, out string key, out string value)
        {
            var index = headerLine.IndexOf(":");
            if (index > 0)
            {
                key = headerLine.Substring(0, index).Trim();
                value = headerLine.Substring(index + 1).Trim();
            }
            throw new IOException("Invalid header");
        }

        internal static long ParseContentLength(Dictionary<string, List<string>> headers)
        {
            return Int64.Parse(headers.GetValueOrDefault("Content-Length")?[0] ?? "-1");
        }

        private static bool ShouldKeepAlive(Dictionary<string, List<string>> headers)
        {
            var value = headers.GetValueOrDefault("Connection")?[0] ?? "close";
            if (value.Equals("keep-alive", StringComparison.InvariantCultureIgnoreCase))
            {
                return true;
            }
            return false;
        }

        internal static RequestContext ParseContext(TcpClient tcp)
        {
            string path = "/";
            Dictionary<string, List<string>> headers = new();
            byte[]? body = null;
            var io = tcp.GetStream();
            var first = true;
            foreach (var line in LineReader.ReadLines(io))
            {
                if (first)
                {
                    path = ParseRequestStatusLine(line);
                    first = false;
                }
                ParseHeader(line, out string headerName, out string headerValue);
                var values = headers.GetValueOrDefault(headerName, new List<string>());
                values.Add(headerName);
                headers[headerName] = values;
            }
            var contentLength = ParseContentLength(headers);
            if (contentLength > 0)
            {
                body = new byte[contentLength];
                using var ms = new MemoryStream(body);
                io.CopyTo(ms, contentLength);
                ms.Close();
            }
            return new RequestContext(path, headers, body, tcp, ShouldKeepAlive(headers));
        }

        internal static void CopyTo(this Stream stream, Stream destination, long limit = Int64.MaxValue)
        {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.Read(buffer, 0, (int)Math.Min(buffer.Length, limit))) != 0)
            {
                destination.Write(buffer, 0, read);
                limit -= read;
            }
        }
    }
}
