package xdman.monitoring;

import java.io.IOException;
import java.io.OutputStream;

import xdman.network.http.HeaderCollection;

public class Response {
	private int code;
	private String message;
	private HeaderCollection headers;
	private byte[] body;

	public void write(OutputStream out) throws IOException {
		StringBuffer buf = new StringBuffer();
		buf.append("HTTP/1.1 " + code + " " + message + "\r\n");
		if (body != null) {
			if (code != 204) {
				headers.addHeader("Content-Length", (body == null || body.length < 1) ? "0" : body.length + "");
			}
		} else {
			if (code != 204) {
				headers.addHeader("Content-Length", "0");
			}
		}

		headers.appendToBuffer(buf);
		buf.append("\r\n");
		if (code != 204) {
			out.write(buf.toString().getBytes());
			if (body != null && body.length > 0) {
				out.write(body);
			}
		} else {
			out.write(buf.toString().getBytes());
		}

		out.flush();
	}

	public final int getCode() {
		return code;
	}

	public final void setCode(int code) {
		this.code = code;
	}

	public final String getMessage() {
		return message;
	}

	public final void setMessage(String message) {
		this.message = message;
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
}
