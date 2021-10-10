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

package xdman.monitoring;

import java.io.IOException;
import java.io.OutputStream;

import xdman.network.http.HeaderCollection;

@SuppressWarnings("unused")
public class Response {

	private int code;
	private String message;
	private HeaderCollection headers;
	private byte[] body;

	public void write(OutputStream out) throws IOException {
		StringBuffer buf = new StringBuffer();
		buf.append("HTTP/1.1 ").append(code).append(" ").append(message).append("\r\n");
		if (body != null) {
			if (code != 204) {
				headers.addHeader("Content-Length", body.length < 1 ? "0" : body.length + "");
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
