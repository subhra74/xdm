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
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Clients.Http
{
    internal class DotNetHttpSession : IHttpSession
    {
        private CancellationTokenSource cancellationTokenSource = new();

        internal CancellationToken CancellationToken => cancellationTokenSource.Token;

        internal HttpRequestMessage? Request { get; set; }

        internal HttpResponseMessage? Response { get; set; }

        public string? ContentType => Response?.Content?.Headers?.ContentType?.MediaType;

        public string? ContentDispositionFileName => GetContentDisposition();

        public long ContentLength => Response?.Content?.Headers?.ContentLength ?? Response?.Content?.Headers?.ContentRange?.Length ?? -1;

        public DateTime LastModified => Response!.Content?.Headers?.LastModified?.Date ?? DateTime.Now;

        public HttpStatusCode StatusCode => Response!.StatusCode;

        public Uri ResponseUri => Response!.RequestMessage!.RequestUri;

        public void Abort()
        {
            cancellationTokenSource.Cancel();
            Response?.Dispose();
        }

        public void AddRange(long range)
        {
            Request!.Headers.Range = new RangeHeaderValue(range, null);
        }

        public void AddRange(long start, long end)
        {
            Request!.Headers.Range = new RangeHeaderValue(start, end);
        }

        public void Close()
        {
            Response?.Dispose();
        }

        public void Dispose()
        {
            Response?.Dispose();
        }

        public Stream GetResponseStream()
        {
            return Response!.Content.ReadAsStreamAsync(this.CancellationToken).Result;
        }

        public string? ReadAsString(CancelFlag cancellationToken)
        {
            return Response?.Content.ReadAsStringAsync(this.CancellationToken).Result;
        }

        public void EnsureSuccessStatusCode()
        {
            WebRequestExtensions.EnsureSuccessStatusCode(Response!.StatusCode, Response!.ReasonPhrase);
        }

        private string? GetContentDisposition()
        {
            if (Response!.Content.Headers.TryGetValues("Content-Disposition", out IEnumerable<string>? values) && values != null)
            {
                var cd = values.FirstOrDefault();
                if (!string.IsNullOrWhiteSpace(cd))
                {
                    return WebRequestExtensions.GetContentDispositionFileName(cd!);
                }
            }
            return null;
        }

        public long GetTotalLengthFromContentRange()
        {
            var len = Response?.Content?.Headers?.ContentRange?.Length ?? -1;
            return len;
        }
    }
}
#endif