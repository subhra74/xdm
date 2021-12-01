//using System;
//using System.Collections.Generic;
//using System.Net;
//using TraceLog;
//using XDM.Core.Lib.Common;

//namespace XDM.Core.Lib.Downloader
//{
//    public class HttpClient : IDisposable
//    {
//        private readonly string connectionGroupName = Guid.NewGuid().ToString();
//        private HashSet<ServicePoint> servicePoints = new HashSet<ServicePoint>();
//        private bool disposed;
//        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(100);

//        public HttpWebRequest CreateRequest(Uri uri)
//        {
//            if (disposed)
//            {
//                throw new ObjectDisposedException("HttpWebRequestClient");
//            }

//            var http = (HttpWebRequest)WebRequest.Create(uri);
//            http.Timeout = http.ReadWriteTimeout = (int)Timeout.TotalMilliseconds;
//            http.UseDefaultCredentials = true;
//            http.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
//            http.AllowAutoRedirect = true;

//            http.ConnectionGroupName = this.connectionGroupName;
//            var sp = http.ServicePoint;
//            if (sp != null)
//            {
//                servicePoints.Add(sp);
//            }
//            return http;
//        }

//        public HttpWebRequest CreateGetRequest(Uri uri,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null)
//        {
//            var req = this.CreateRequest(uri);
//            if (headers != null)
//            {
//                foreach (var e in headers)
//                {
//                    SetHeader(req, e.Key, e.Value);
//                }
//            }
//            if (cookies != null)
//            {
//                foreach (var e in cookies)
//                {
//                    SetHeader(req, e.Key, e.Value);
//                }
//            }
//            if (authentication != null && !string.IsNullOrEmpty(authentication.Value.UserName))
//            {
//                req.Credentials = new NetworkCredential(authentication.Value.UserName, authentication.Value.Password);
//            }
//            return req;
//        }

//        public HttpWebRequest CreatePostRequest(Uri uri,
//            Dictionary<string, List<string>>? headers = null,
//            Dictionary<string, string>? cookies = null,
//            AuthenticationInfo? authentication = null,
//            byte[]? body = null)
//        {
//            var req = this.CreateRequest(uri);
//            req.Method = "POST";
//            if (headers != null)
//            {
//                foreach (var e in headers)
//                {
//                    SetHeader(req, e.Key, e.Value);
//                }
//            }
//            if (cookies != null)
//            {
//                foreach (var e in cookies)
//                {
//                    SetHeader(req, e.Key, e.Value);
//                }
//            }
//            if (authentication != null && !string.IsNullOrEmpty(authentication.Value.UserName))
//            {
//                req.Credentials = new NetworkCredential(authentication.Value.UserName, authentication.Value.Password);
//            }
//            if (body != null)
//            {
//                req.ContentLength = body.Length;
//                using var rs = req.GetRequestStream();
//                rs.Write(body, 0, body.Length);
//                rs.Close();
//            }
//            return req;
//        }

//        public HttpWebResponse Send(HttpWebRequest request)
//        {
//            HttpWebResponse response;
//            try
//            {
//                response = (HttpWebResponse)request.GetResponse();
//            }
//            catch (WebException we)
//            {
//                Log.Debug(we, we.Message);
//                if (we.Response == null)
//                {
//                    throw new Exception("Connectivity error");
//                }
//                response = (HttpWebResponse?)we.Response!;
//                response.Discard();
//                response.Close();
//            }

//            var servicePoint = request.ServicePoint;
//            if (servicePoint != null)
//            {
//                servicePoints.Add(servicePoint);
//            }
//            return response;
//        }

//        public void Dispose()
//        {
//            lock (this)
//            {
//                try
//                {
//                    disposed = true;
//                    foreach (var servicePoint in servicePoints)
//                    {
//                        Log.Debug("Disposing service point");
//                        Log.Debug("ConnectionName: " + servicePoint.ConnectionName +
//                            "\nCurrentConnections: " + servicePoint.CurrentConnections +
//                            "\nAddress: " + servicePoint.Address +
//                            "\nGetHashCode(): " + servicePoint.GetHashCode());
//                        servicePoint.CloseConnectionGroup(this.connectionGroupName);
//                    }
//                }
//                catch { }
//            }
//        }

//        public void Close()
//        {
//            this.Dispose();
//        }

//        private static void SetHeader(HttpWebRequest request, string key, IEnumerable<string> values)
//        {
//            try
//            {
//                foreach (var value in values)
//                {
//                    SetHeader(request, key, value);
//                }
//            }
//            catch (Exception ex)
//            {
//                Log.Debug(ex, "Error setting header value");
//            }
//        }

//        private static void SetHeader(HttpWebRequest request, string key, string value)
//        {
//            switch (key.ToLowerInvariant())
//            {
//                case "accept":
//                    request.Accept = value;
//                    break;
//                case "connection":
//                    request.Connection = value;
//                    break;
//                case "content-type":
//                    request.ContentType = value;
//                    break;
//                case "expect":
//                    request.Expect = value;
//                    break;
//#if !NET35
//                case "date":
//                    request.Date = DateTime.Parse(value);
//                    break;
//                case "host":
//                    request.Host = value;
//                    break;
//#endif
//                case "if-modified-since":
//                    request.IfModifiedSince = DateTime.Parse(value);
//                    break;
//                case "referer":
//                    request.Referer = value;
//                    break;
//                case "user-agent":
//                    request.UserAgent = value;
//                    break;
//                case "transfer-encoding":
//                    request.TransferEncoding = value;
//                    break;
//                case "range":
//                case "content-length":
//                    break;
//                default:
//                    request.Headers.Add(key, value);
//                    break;
//            }
//        }
//    }
//}
