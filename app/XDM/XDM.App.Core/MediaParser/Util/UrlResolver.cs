using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace MediaParser.Util
{
    internal static class UrlResolver
    {
        internal static Uri Resolve(Uri baseUrl, string url)
        {
            if (url.StartsWith("https://") || url.StartsWith("http://"))
            {
                return new Uri(url);
            }
            return new Uri(baseUrl, url);
        }

    }
}
