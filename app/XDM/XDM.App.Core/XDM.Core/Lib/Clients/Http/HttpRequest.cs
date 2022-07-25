using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    public class HttpRequest
    {
        //private Uri uri;
        //private Dictionary<string, List<string>>? headers = null;
        //private Dictionary<string, string>? cookies = null;
        //private AuthenticationInfo? authentication = null;
        //private long rangeStart = 0, rangeEnd = -1;

        //public HttpRequest(Uri uri,
        //    Dictionary<string, List<string>>? headers,
        //    Dictionary<string, string>? cookies,
        //    AuthenticationInfo? authentication)
        //{
        //    this.uri = uri;
        //    this.headers = headers;
        //    this.cookies = cookies;
        //    this.authentication = authentication;
        //}

        //public Uri Uri => uri;
        //public Dictionary<string, List<string>>? Headers => headers;
        //public Dictionary<string, string>? Cookies => cookies;
        //public AuthenticationInfo? Authentication => authentication;
        //public long RangeStart => rangeStart;
        //public long RangeEnd => rangeEnd;

        internal IHttpSession? Session { get; set; }

        public void AddRange(long range)
        {
            this.Session!.AddRange(range);
        }

        public void AddRange(long start, long end)
        {
            this.Session!.AddRange(start, end);
        }

        public void Abort()
        {
            this.Session?.Abort();
        }
    }
}
