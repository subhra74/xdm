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

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.tinylog.Logger;

import xdman.network.http.HeaderCollection;
import xdman.util.NetUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings("unused")
public class ParsedHookData {

	@Override
	public String toString() {
		return "ParsedHookData [url=" + url + ", file=" + file + ", contentLength=" + contentLength + ", contentType="
				+ contentType + ", ext=" + ext + "]";
	}

	public static List<ParsedHookData> parseLinks(byte[] b) throws UnsupportedEncodingException {
		List<ParsedHookData> list = new ArrayList<>();
		String strBuf = new String(b, StandardCharsets.UTF_8);
		String[] arr = strBuf.split("\r\n\r\n");
		for (String str : arr) {
			list.add(ParsedHookData.parse(str.getBytes()));
		}
		return list;
	}

	public static ParsedHookData parse(byte[] b) throws UnsupportedEncodingException {
		ParsedHookData data = new ParsedHookData();
		Map<String, String> cookies = new HashMap<>();
		data.requestHeaders = new HeaderCollection();
		data.responseHeaders = new HeaderCollection();
		String strBuf = new String(b, StandardCharsets.UTF_8);
		String[] arr = strBuf.split("\r\n");
		for (String str : arr) {
			if (!str.contains("=")) {
				continue;
			}
			int index = str.indexOf("=");
			String key = str.substring(0, index).trim().toLowerCase();
			String val = str.substring(index + 1).trim();
			switch (key) {
				case "url":
					data.setUrl(val);
					break;
				case "file":
					val = XDMUtils.getFileName(val);
					data.setFile(val);
					break;
				case "req":
					index = val.indexOf(":");
					if (index > 0) {
						String headerName = val.substring(0, index).trim().toLowerCase();
						String headerValue = val.substring(index + 1).trim();
						if (headerName.equals("range") && (!headerValue.startsWith("bytes=0-"))) {
							data.setPartialResponse(true);
						}
						if (!isBlockedHeader(headerName)) {
							data.requestHeaders.addHeader(headerName, headerValue);
						}
						if (headerName.equals("cookie")) {
							parseCookies(headerValue, cookies);
						}
						Logger.info(str);
					}
					break;
				case "res":
					index = val.indexOf(":");
					if (index > 0) {
						String headerName = val.substring(0, index).trim().toLowerCase();
						String headerValue = val.substring(index + 1).trim();
						data.responseHeaders.addHeader(headerName, headerValue);
					}
					break;
				case "cookie":
					index = val.indexOf(":");
					if (index > 0) {
						String cookieName = val.substring(0, index).trim();
						String cookieValue = val.substring(index + 1).trim();
						cookies.put(cookieName, cookieValue);

					}
					break;
			}
		}
		if (data.responseHeaders.containsHeader("content-length")
				|| data.responseHeaders.containsHeader("content-range")) {
			data.contentLength = NetUtils.getContentLength(data.responseHeaders);
		}
		if (data.responseHeaders.containsHeader("content-type")) {
			data.contentType = NetUtils.getCleanContentType(data.responseHeaders.getValue("content-type"));
		}
		if (!data.requestHeaders.containsHeader("user-agent")) {
			if (data.responseHeaders.containsHeader("realua")) {
				data.requestHeaders.addHeader("user-agent", data.responseHeaders.getValue("realua"));
			}
		}

		for (String cookieKeys : cookies.keySet()) {
			data.requestHeaders.addHeader("Cookie", cookieKeys + "=" + cookies.get(cookieKeys));
		}

		try {
			data.setExt(XDMUtils.getExtension(XDMUtils.getFileName(data.getUrl())));
		} catch (Exception e) {
			Logger.error(e);
		}
		return data;
	}

	private static void parseCookies(String value, Map<String, String> cookieMap) {
		if (StringUtils.isNullOrEmptyOrBlank(value)) {
			return;
		}
		String[] arr = value.split(";");
		for (String str : arr) {
			try {
				String[] s = str.trim().split("=");
				cookieMap.put(s[0].trim(), s[1].trim());
			} catch (Exception e) {
				Logger.error(e);
			}
		}
	}

	private static boolean isBlockedHeader(String name) {
		for (String blockedHeader : blockedHeaders) {
			if (name.startsWith(blockedHeader)) {
				return true;
			}
		}
		return false;
	}

	private static final String[] blockedHeaders = { "accept", "if", "authorization", "proxy", "connection", "expect", "TE",
			"upgrade", "range", "cookie" };

	private String url, file;
	private HeaderCollection requestHeaders;
	private HeaderCollection responseHeaders;
	private long contentLength;
	private String contentType;
	private String ext;
	private boolean partialResponse;

	public final String getUrl() {
		return url;
	}

	public final void setUrl(String url) {
		this.url = url;
	}

	public final String getFile() {
		return file;
	}

	public final void setFile(String file) {
		this.file = file;
	}

	public final HeaderCollection getRequestHeaders() {
		return requestHeaders;
	}

	public final void setRequestHeaders(HeaderCollection requestHeaders) {
		this.requestHeaders = requestHeaders;
	}

	public final HeaderCollection getResponseHeaders() {
		return responseHeaders;
	}

	public final void setResponseHeaders(HeaderCollection responseHeaders) {
		this.responseHeaders = responseHeaders;
	}

	public final long getContentLength() {
		return contentLength;
	}

	public final void setContentLength(long contentLength) {
		this.contentLength = contentLength;
	}

	public final String getContentType() {
		return contentType;
	}

	public final void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getExt() {
		return ext;
	}

	public void setExt(String ext) {
		this.ext = ext;
	}

	public boolean isPartialResponse() {
		return partialResponse;
	}

	public void setPartialResponse(boolean partialResponse) {
		this.partialResponse = partialResponse;
	}
}
