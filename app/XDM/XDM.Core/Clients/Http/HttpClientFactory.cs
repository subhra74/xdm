using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core;

namespace XDM.Core.Clients.Http
{
    public static class HttpClientFactory
    {
        public static IHttpClient NewHttpClient(ProxyInfo? proxyInfo)
        {
            ProxyInfo? proxy = null;
            if (proxyInfo.HasValue)
            {
                if (proxyInfo.Value.ProxyType != ProxyType.Custom)
                {
                    proxy = proxyInfo;
                }
                else if (!string.IsNullOrEmpty(proxyInfo.Value.Host) && proxyInfo.Value.Port > 0)
                {
                    proxy = proxyInfo;
                }
            }

            if (Environment.Version.Major == 2)
            {
                return new WinHttpClient(proxy);
            }
            else
            {
#if NET5_0_OR_GREATER
                return new DotNetHttpClient(proxy);
#else
                return new NetFxHttpClient(proxy);
#endif
            }
        }
    }
}
