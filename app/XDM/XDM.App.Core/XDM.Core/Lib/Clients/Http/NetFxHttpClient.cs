using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using TraceLog;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    internal class NetFxHttpClient : IHttpClient
    {
        private readonly string connectionGroupName = Guid.NewGuid().ToString();
        private HashSet<ServicePoint> servicePoints = new();
        private bool disposed;
        private ProxyInfo? proxy;
        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(100);

        internal NetFxHttpClient(ProxyInfo? proxy)
        {
            this.proxy = proxy;
        }

        private HttpWebRequest CreateRequest(Uri uri)
        {
            if (disposed)
            {
                throw new ObjectDisposedException("HttpWebRequestClient");
            }

            var http = (HttpWebRequest)WebRequest.Create(uri);
            var p = ProxyHelper.GetProxy(this.proxy);
            if (p != null)
            {
                http.Proxy = p;
            }
            http.Timeout = http.ReadWriteTimeout = (int)Timeout.TotalMilliseconds;
            http.UseDefaultCredentials = true;
            http.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
            http.AllowAutoRedirect = true;

            http.ConnectionGroupName = this.connectionGroupName;
            var sp = http.ServicePoint;
            lock (servicePoints)
            {
                if (sp != null)
                {
                    servicePoints.Add(sp);
                }
            }
            return http;
        }

        public HttpRequest CreateGetRequest(Uri uri,
            Dictionary<string, List<string>>? headers = null,
            Dictionary<string, string>? cookies = null,
            AuthenticationInfo? authentication = null)
        {
            var req = this.CreateRequest(uri);
            if (headers != null)
            {
                foreach (var e in headers)
                {
                    SetHeader(req, e.Key, e.Value);
                }
            }
            if (cookies != null)
            {
                foreach (var e in cookies)
                {
                    SetHeader(req, e.Key, e.Value);
                }
            }
            if (authentication != null && !string.IsNullOrEmpty(authentication.Value.UserName))
            {
                req.Credentials = new NetworkCredential(authentication.Value.UserName, authentication.Value.Password);
            }

            return new HttpRequest { Session = new NetFxHttpSession { Request = req } };
        }

        public HttpRequest CreatePostRequest(Uri uri,
            Dictionary<string, List<string>>? headers = null,
            Dictionary<string, string>? cookies = null,
            AuthenticationInfo? authentication = null,
            byte[]? body = null)
        {
            var req = this.CreateRequest(uri);
            req.Method = "POST";
            if (headers != null)
            {
                foreach (var e in headers)
                {
                    SetHeader(req, e.Key, e.Value);
                }
            }
            if (cookies != null)
            {
                foreach (var e in cookies)
                {
                    SetHeader(req, e.Key, e.Value);
                }
            }
            if (authentication != null && !string.IsNullOrEmpty(authentication.Value.UserName))
            {
                req.Credentials = new NetworkCredential(authentication.Value.UserName, authentication.Value.Password);
            }
            if (body != null)
            {
                req.ContentLength = body.Length;
                using var rs = req.GetRequestStream();
                rs.Write(body, 0, body.Length);
                rs.Close();
            }
            return new HttpRequest { Session = new NetFxHttpSession { Request = req } };
        }

        public HttpResponse Send(HttpRequest request)
        {
            HttpWebRequest r;
            HttpWebResponse response;
            if (request.Session == null)
            {
                throw new ArgumentNullException(nameof(request.Session));
            }
            if (request.Session is not NetFxHttpSession session)
            {
                throw new ArgumentNullException(nameof(request.Session));
            }
            if (session.Request == null)
            {
                throw new ArgumentNullException(nameof(session.Request));
            }
            r = session.Request;
            try
            {
                response = (HttpWebResponse)r.GetResponse();
            }
            catch (WebException we)
            {
                Log.Debug(we, we.Message);
                if (we.Response == null)
                {
                    throw new Exception("Connectivity error");
                }
                response = (HttpWebResponse?)we.Response!;
                response.Discard();
                response.Close();
            }

            var servicePoint = r.ServicePoint;
            if (servicePoint != null)
            {
                servicePoints.Add(servicePoint);
            }
            session.Response = response;
            return new HttpResponse { Session = session };
        }

        public void Dispose()
        {
            lock (this)
            {
                try
                {
                    disposed = true;
                    foreach (var servicePoint in servicePoints)
                    {
                        Log.Debug("Disposing service point");
                        Log.Debug("ConnectionName: " + servicePoint.ConnectionName +
                            "\nCurrentConnections: " + servicePoint.CurrentConnections +
                            "\nAddress: " + servicePoint.Address +
                            "\nGetHashCode(): " + servicePoint.GetHashCode());
                        servicePoint.CloseConnectionGroup(this.connectionGroupName);
                    }
                }
                catch { }
            }
        }

        public void Close()
        {
            this.Dispose();
        }

        private static void SetHeader(HttpWebRequest request, string key, IEnumerable<string> values)
        {
            try
            {
                foreach (var value in values)
                {
                    SetHeader(request, key, value);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error setting header value");
            }
        }

        private static void SetHeader(HttpWebRequest request, string key, string value)
        {
            switch (key.ToLowerInvariant())
            {
                case "accept":
                    request.Accept = value;
                    break;
                case "connection":
                    request.Connection = value;
                    break;
                case "content-type":
                    request.ContentType = value;
                    break;
                case "expect":
                    request.Expect = value;
                    break;
#if !NET35
                case "date":
                    request.Date = DateTime.Parse(value);
                    break;
                case "host":
                    request.Host = value;
                    break;
#endif
                case "if-modified-since":
                    request.IfModifiedSince = DateTime.Parse(value);
                    break;
                case "referer":
                    request.Referer = value;
                    break;
                case "user-agent":
                    request.UserAgent = value;
                    break;
                case "transfer-encoding":
                    request.TransferEncoding = value;
                    break;
                case "range":
                case "content-length":
                    break;
                default:
                    request.Headers.Add(key, value);
                    break;
            }
        }
    }
}
