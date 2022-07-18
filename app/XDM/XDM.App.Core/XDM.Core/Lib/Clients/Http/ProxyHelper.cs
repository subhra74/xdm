using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using TraceLog;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Clients.Http
{
    internal static class ProxyHelper
    {
        internal static IWebProxy? GetProxy(ProxyInfo? proxy)
        {
            if (proxy.HasValue)
            {
                Log.Debug("Proxy type: " + proxy.Value.ProxyType);
                if (proxy.Value.ProxyType == ProxyType.Direct)
                {
                    return new WebProxy();
                }
                else if (proxy.Value.ProxyType == ProxyType.Custom)
                {
                    var p = new WebProxy(proxy.Value.Host, proxy.Value.Port);
                    if (!string.IsNullOrEmpty(proxy.Value.UserName))
                    {
                        p.Credentials = new NetworkCredential(proxy.Value.UserName, proxy.Value.Password);
                    }
                    return p;
                }
            }
            return null;
        }
    }
}
