package xdman.network.http;

import java.io.*;
import java.net.*;
import java.util.*;

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

	private String _url;
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

		// System.out
		// .println(hc.getResponseCode() + " " + hc.getResponseMessage());

		this.statusCode = hc.getResponseCode();
		this.statusMessage = hc.getResponseMessage();

		Map<String, List<String>> responseHeaderMap = hc.getHeaderFields();

		Iterator<String> headerIterator = responseHeaderMap.keySet().iterator();
		while (headerIterator.hasNext()) {
			String key = headerIterator.next();
			if (key == null)
				continue;
			List<String> headerValues = responseHeaderMap.get(key);
			Iterator<String> headerValueIterator = headerValues.iterator();
			while (headerValueIterator.hasNext()) {
				String value = headerValueIterator.next();
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

	// public static void main(String[] args) throws IOException {
	// JavaHttpClient hc = new JavaHttpClient(
	// "https://intraeasy.techmahindra.com/CIOPRD1_DOWN/CIOPRD11.html");
	// // hc.credentialMgr = new TestCredentialMgr();
	// // hc.setProxyResolver(new TestProxyResolver());
	// hc.connect();
	// System.out.println(hc.getResponseHeader("location") + " "
	// + hc.getContentLength());
	// }

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
