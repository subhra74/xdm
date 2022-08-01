using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    internal interface IHttpSession : IDisposable
    {
        public void AddRange(long range);

        public void AddRange(long start, long end);

        public string? ContentType { get; }

        public string? ContentDispositionFileName { get; }

        public long ContentLength { get; }

        public DateTime LastModified { get; }

        public HttpStatusCode StatusCode { get; }

        public string ReadAsString(CancelFlag cancellationToken);

        public void EnsureSuccessStatusCode();

        public Uri ResponseUri { get; }

        public void Close();

        public Stream GetResponseStream();

        public void Abort();

        public long GetTotalLengthFromContentRange();
    }
}
