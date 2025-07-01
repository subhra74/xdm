package xdman.network;

import xdman.Config;
import xdman.network.http.WebProxy;

public class ProxyResolver {
	public static WebProxy resolve(String url) {

		Config config = Config.getInstance();
		int proxyMode = config.getProxyMode();
		if (proxyMode == 1) {
			// try {
			// String pacUrl = config.getProxyPac();
			// AutoProxyHandler pacHandler = AutoProxyHandler.getInstance();
			// String pacUrl2 = pacHandler.getPacUrl();
			// if (pacUrl2 == null || pacUrl2.compareTo(pacUrl) != 0) {
			// pacHandler.setPacUrl(pacUrl);
			// }
			// return pacHandler.getProxyForUrl(url);
			// } catch (Exception e) {
			// return null;
			// }

		}
		if (proxyMode == 2) {
			if (config.getProxyHost() == null || config.getProxyHost().length() < 1) {
				return null;
			}
			if (config.getProxyPort() < 1) {
				return null;
			}
			return new WebProxy(config.getProxyHost(), config.getProxyPort());
		}
		if (proxyMode == 3) {
			if (config.getSocksHost() == null || config.getSocksHost().length() < 1) {
				return null;
			}
			if (config.getSocksPort() < 1) {
				return null;
			}
			WebProxy wp = new WebProxy(config.getSocksHost(), config.getSocksPort());
			wp.setSocks(true);
			return wp;
		}
		return null;
	}
}
