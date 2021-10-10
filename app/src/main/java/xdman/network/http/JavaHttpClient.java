/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.network.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import xdman.network.ProxyResolver;

public class JavaHttpClient extends HttpClient {

	private HttpURLConnection hc;
	private boolean followRedirect = false;

	public JavaHttpClient(String url) {
		super();
		this._url = url;
		this.requestHeaders = new HeaderCollection();
		this.responseHeaders = new HeaderCollection();
	}

	private final String _url;
	private URL realURL;

	public void connect() throws IOException {
		HttpContext.getInstance().init();
		WebProxy webproxy = ProxyResolver.resolve(_url);
		URL url = new URL(_url);
		this.realURL = url;
		if (webproxy != null) {
			Proxy proxy = new Proxy(webproxy.isSocks() ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
					new InetSocketAddress(webproxy.getHost(), webproxy.getPort()));
			hc = (HttpURLConnection) url.openConnection(proxy);
		} else {
			hc = (HttpURLConnection) url.openConnection();
		}
		Iterator<HttpHeader> headers = requestHeaders.getAll();
		while (headers.hasNext()) {
			HttpHeader header = headers.next();
			hc.addRequestProperty(header.getName(), header.getValue());
		}
		hc.setInstanceFollowRedirects(this.followRedirect);

		this.statusCode = hc.getResponseCode();
		this.statusMessage = hc.getResponseMessage();

		Map<String, List<String>> responseHeaderMap = hc.getHeaderFields();

		for (String key : responseHeaderMap.keySet()) {
			if (key == null)
				continue;
			List<String> headerValues = responseHeaderMap.get(key);
			for (String value : headerValues) {
				HttpHeader header = new HttpHeader(key, value);
				this.responseHeaders.addHeader(header);
			}
		}
	}

	public void setFollowRedirect(boolean followRedirect) {
		this.followRedirect = followRedirect;
	}

	@Override
	public long getContentLength() throws IOException {
		return hc.getContentLengthLong();
	}

	@Override
	public void dispose() {
		hc.disconnect();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return hc.getInputStream();
	}

	@Override
	public String getHost() {
		return realURL.getHost() + (realURL.getPort() > 0 ? ":" + realURL.getPort() : "");
	}

}
