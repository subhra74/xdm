package xdman.network;

import java.net.*;

import xdman.util.StringUtils;

public class ParsedURL {
	private String _url;
	private int port;
	private String host;
	private String pathAndQuery;
	private String protocol;

	public static ParsedURL parse(String urlString) {
		try {
			URL url = new URL(urlString);
			ParsedURL parsedURL = new ParsedURL();
			parsedURL._url = urlString;
			parsedURL.host = url.getHost();
			parsedURL.port = url.getPort();
			if (parsedURL.port < 0) {
				parsedURL.port = url.getDefaultPort();
			}
			parsedURL.protocol = url.getProtocol();
			parsedURL.pathAndQuery = url.getPath();
			if (StringUtils.isNullOrEmptyOrBlank(parsedURL.pathAndQuery)) {
				parsedURL.pathAndQuery = "/";
			}
			String query = url.getQuery();
			if (!StringUtils.isNullOrEmptyOrBlank(query)) {
				parsedURL.pathAndQuery += "?" + query;
			}
			return parsedURL;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public String toString() {
		return _url;
	}

	public int getPort() {
		return port;
	}

	public String getHost() {
		return host;
	}

	public String getPathAndQuery() {
		return pathAndQuery;
	}

	public String getProtocol() {
		return protocol;
	}
}
