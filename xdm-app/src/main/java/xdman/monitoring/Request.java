package xdman.monitoring;

import java.io.IOException;
import java.io.InputStream;

import xdman.network.http.HeaderCollection;
import xdman.util.NetUtils;

public class Request {
	private String url;
	private HeaderCollection headers;
	private byte[] body;
	private int method;

	public void read(InputStream in) throws IOException {
		String reqLine = NetUtils.readLine(in);
		//System.out.println(reqLine);
		if (reqLine == null || reqLine.length() < 1) {
			throw new IOException("Invalid request line: " + reqLine);
		}
		String[] arr = reqLine.split(" ");
		if (arr.length != 3) {
			throw new IOException("Invalid request: " + reqLine);
		}
		this.url = arr[1];
		this.method = arr[0].toLowerCase().equals("post") ? 1 : 2;
		this.headers = new HeaderCollection();
		headers.loadFromStream(in);
		String header = headers.getValue("Content-Length");
		if (header != null) {
			long len = Long.parseLong(header);
			body = new byte[(int) len];
			int off = 0;
			while (len > 0) {
				int x = in.read(body, off, body.length - off);
				if (x == -1) {
					throw new IOException("Unexpected EOF");
				}
				len -= x;
				off += x;
			}
		}
	}

	public final String getUrl() {
		return url;
	}

	public final void setUrl(String url) {
		this.url = url;
	}

	public final HeaderCollection getHeaders() {
		return headers;
	}

	public final void setHeaders(HeaderCollection headers) {
		this.headers = headers;
	}

	public final byte[] getBody() {
		return body;
	}

	public final void setBody(byte[] body) {
		this.body = body;
	}

	public final int getMethod() {
		return method;
	}

	public final void setMethod(int method) {
		this.method = method;
	}
}
