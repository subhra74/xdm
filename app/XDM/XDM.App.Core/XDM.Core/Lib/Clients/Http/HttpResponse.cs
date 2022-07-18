using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Clients.Http
{
    public class HttpResponse : IDisposable
    {
        internal IHttpSession? Session { get; set; }
        public void EnsureSuccessStatusCode() => Session!.EnsureSuccessStatusCode();

        public string ContentType => Session!.ContentType;

        public HttpStatusCode StatusCode => Session!.StatusCode;

        public long ContentLength => Session!.ContentLength;
        public long ContentRangeLength => Session!.GetTotalLengthFromContentRange();

        public string ReadAsString(CancelFlag cancellationToken) => Session!.ReadAsString(cancellationToken);

        public Uri ResponseUri => Session!.ResponseUri;

        public string? ContentDispositionFileName => Session!.ContentDispositionFileName;

        public DateTime LastModified => Session!.LastModified;

        public void Dispose()
        {
            this.Close();
        }

        public void Close()
        {
            Session?.Close();
        }

        public Stream GetResponseStream() => Session!.GetResponseStream();
    }
}
