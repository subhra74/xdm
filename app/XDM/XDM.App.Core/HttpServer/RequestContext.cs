using System;
using System.Collections.Generic;
using System.Net.Sockets;
using System.Text;
#if !NET5_0_OR_GREATER
using NetFX.Polyfill;
#endif

namespace HttpServer
{
    public class RequestContext
    {
        private TcpClient tcp;
        public string RequestPath { get; }
        public byte[]? RequestBody { get; }
        public Dictionary<string, List<string>> RequestHeaders { get; }
        public byte[]? ResponseBody { set; get; }
        public Dictionary<string, List<string>> ResponseHeaders { set; get; }
        public ResponseStatus ResponseStatus { set; get; }
        public bool KeepAlive { get; private set; }

        internal RequestContext(string path, Dictionary<string, List<string>> headers, byte[]? body, TcpClient tcp, bool keepAlive)
        {
            this.RequestPath = path;
            this.RequestHeaders = headers;
            this.RequestBody = body;
            this.tcp = tcp;
            this.ResponseHeaders = new();
            this.ResponseStatus = new ResponseStatus { StatusCode = 200, StatusMessage = "OK" };
            this.KeepAlive = keepAlive;
        }

        public void SendResponse()
        {
            var io = this.tcp.GetStream();
            var responseBuffer = new StringBuilder();
            responseBuffer.Append($"HTTP/1.0 {this.ResponseStatus.StatusCode} {this.ResponseStatus.StatusMessage}\r\n");
            foreach (var headerName in ResponseHeaders.Keys)
            {
                if (headerName.Equals("content-length", StringComparison.InvariantCultureIgnoreCase))
                {
                    continue;
                }
                foreach (var value in ResponseHeaders[headerName])
                {
                    responseBuffer.Append($"{headerName}: {value}\r\n");
                }
            }
            responseBuffer.Append($"Connection: keep-alive\r\n");
            if (ResponseBody != null && ResponseBody.Length > 0)
            {
                responseBuffer.Append($"Content-Length: {ResponseBody.Length}\r\n");
            }
            responseBuffer.Append("\r\n");
            var bytes = Encoding.UTF8.GetBytes(responseBuffer.ToString());
            io.Write(bytes, 0, bytes.Length);
            if (ResponseBody != null && ResponseBody.Length > 0)
            {
                io.Write(ResponseBody, 0, ResponseBody.Length);
            }
            io.Flush();
        }

        public void AddResponseHeader(string name, string value)
        {
            var values = this.ResponseHeaders.GetValueOrDefault(name, new List<string>(1));
            values.Add(value);
            this.ResponseHeaders[name] = values;
        }
    }
}
