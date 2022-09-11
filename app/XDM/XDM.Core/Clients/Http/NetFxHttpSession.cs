using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    internal class NetFxHttpSession : IHttpSession
    {
        internal HttpWebRequest? Request { get; set; }

        internal HttpWebResponse? Response { get; set; }

        public string ContentType => Response!.ContentType;

        public bool Compressed => Utils.IsCompressed(Response!.GetResponseHeader("Transfer-Encoding"))
            || Utils.IsCompressed(Response!.ContentEncoding);

        public string? ContentDispositionFileName => Response!.GetContentDispositionFileName();

        public long ContentLength => Response!.GetContentLength();

        public DateTime LastModified => Response!.LastModified;

        public HttpStatusCode StatusCode => Response!.StatusCode;

        public Uri ResponseUri => Response!.ResponseUri;

        public void Abort()
        {
            try { Request!.Abort(); } catch { }
        }

        public void AddRange(long range)
        {
            Request!.AddRange(range);
        }

        public void AddRange(long start, long end)
        {
            Request!.AddRange(start, end);
        }

        public void Close()
        {
            Response!.Close();
        }

        public void Dispose()
        {
#if NET35
            Response!.Close();
#else
            Response!.Dispose();
#endif
        }

        public Stream GetResponseStream()
        {
            return Response!.GetResponseStream();
        }

        public string ReadAsString(CancelFlag cancellationToken)
        {
            return Response!.ReadAsString(cancellationToken);
        }

        public void EnsureSuccessStatusCode()
        {
            Response!.EnsureSuccessStatusCode();
        }

        public long GetTotalLengthFromContentRange()
        {
            var contentRange = Response!.Headers.GetValues("Content-Range")?.First();
            return WebRequestExtensions.ContentLengthFromContentRange(contentRange);
        }
    }
}
