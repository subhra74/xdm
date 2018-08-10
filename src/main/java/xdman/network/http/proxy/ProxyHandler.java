package xdman.network.http.proxy;

import java.net.URL;

public interface ProxyHandler {
	boolean isSupported(int paramInt);

	boolean isProxyCacheSupported();

	void init(BrowserProxyInfo paramBrowserProxyInfo)
			throws Exception;

	ProxyInfo[] getProxyInfo(URL paramURL) throws Exception;
}
