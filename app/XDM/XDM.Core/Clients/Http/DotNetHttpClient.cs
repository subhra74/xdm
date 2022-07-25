#if NET5_0_OR_GREATER
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading;
using TraceLog;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    internal class DotNetHttpClient : IHttpClient
    {
        private bool disposed;
        private HttpClient? hc;
        private CancellationTokenSource cts = new();
        private ProxyInfo? proxy;

        public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(100);

        internal DotNetHttpClient(ProxyInfo? proxy)
        {
            this.proxy = proxy;
        }

        private HttpRequestMessage CreateRequest(Uri uri, HttpMethod method,
            Dictionary<string, List<string>>? headers = null,
            Dictionary<string, string>? cookies = null,
            AuthenticationInfo? authentication = null)
        {
            if (disposed)
            {
                throw new ObjectDisposedException("DotNetHttpClient");
            }

            var http = new HttpRequestMessage
            {
                Method = method,
                RequestUri = uri
            };

            lock (this)
            {
                if (this.hc == null)
                {
                    var handler = new HttpClientHandler
                    {
                        AutomaticDecompression = DecompressionMethods.All,
                        PreAuthenticate = true,
                        UseDefaultCredentials = true,
                        MaxConnectionsPerServer = 100,
                        ServerCertificateCustomValidationCallback = (a, b, c, d) => true
                    };

                    var p = ProxyHelper.GetProxy(this.proxy);
                    if (p != null)
                    {
                        handler.Proxy = p;
                    }

                    if (authentication != null && !string.IsNullOrEmpty(authentication.Value.UserName))
                    {
                        handler.Credentials = new NetworkCredential(authentication.Value.UserName, authentication.Value.Password);
                    }

                    this.hc = new HttpClient(handler)
                    {
                        Timeout = this.Timeout
                    };
                }
            }

            if (headers != null)
            {
                foreach (var e in headers)
                {
                    SetHeader(http, e.Key, e.Value);
                }
            }
            if (cookies != null)
            {
                foreach (var e in cookies)
                {
                    SetHeader(http, e.Key, e.Value);
                }
            }
            return http;
        }

        public HttpRequest CreateGetRequest(Uri uri,
            Dictionary<string, List<string>>? headers = null,
            Dictionary<string, string>? cookies = null,
            AuthenticationInfo? authentication = null)
        {
            var req = this.CreateRequest(uri, HttpMethod.Get, headers, cookies, authentication);
            return new HttpRequest { Session = new DotNetHttpSession { Request = req } };
        }

        public HttpRequest CreatePostRequest(Uri uri,
            Dictionary<string, List<string>>? headers = null,
            Dictionary<string, string>? cookies = null,
            AuthenticationInfo? authentication = null,
            byte[]? body = null)
        {
            var req = this.CreateRequest(uri, HttpMethod.Post, headers, cookies, authentication);
            if (body != null)
            {
                req.Content = new ByteArrayContent(body);
                if (headers != null && headers.TryGetValue("Content-Type", out List<string>? values))
                {
                    if (values != null && values.Count > 0)
                    {
                        req.Content.Headers.ContentType = new MediaTypeHeaderValue(values[0]);
                    }
                }
            }
            return new HttpRequest { Session = new DotNetHttpSession { Request = req } };
        }

        public HttpResponse Send(HttpRequest request)
        {
            HttpRequestMessage r;
            HttpResponseMessage? response = null;
            if (request.Session == null)
            {
                throw new ArgumentNullException(nameof(request.Session));
            }
            if (request.Session is not DotNetHttpSession session)
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
                response = this.hc!.Send(r, HttpCompletionOption.ResponseHeadersRead, cts.Token);
                response.EnsureSuccessStatusCode();
            }
            catch (HttpRequestException we)
            {
                Log.Debug(we, we.Message);
                response?.Dispose();
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
                    cts.Cancel();
                    this.hc?.Dispose();
                }
                catch { }
            }
        }

        public void Close()
        {
            this.Dispose();
        }

        private void SetHeader(HttpRequestMessage request, string key, IEnumerable<string> values)
        {
            try
            {
                foreach (var value in values)
                {
                    if (!string.Equals(key, "Content-Type", StringComparison.InvariantCultureIgnoreCase))
                    {
                        request.Headers.TryAddWithoutValidation(key, value);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error setting header value");
            }
        }

        private void SetHeader(HttpRequestMessage request, string key, string value)
        {
            try
            {
                if (!string.Equals(key, "Content-Type", StringComparison.InvariantCultureIgnoreCase))
                {
                    request.Headers.TryAddWithoutValidation(key, value);
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error setting header value");
            }
        }
    }
}
#endif