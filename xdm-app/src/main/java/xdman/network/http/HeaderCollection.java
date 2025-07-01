package xdman.network.http;

import java.util.*;
import java.io.*;

import xdman.util.NetUtils;

public class HeaderCollection {
	private List<HttpHeader> headers;

	public HeaderCollection() {
		headers = new ArrayList<HttpHeader>();
	}

	public String getValue(String name) {
		for (int i = 0; i < headers.size(); i++) {
			HttpHeader header = headers.get(i);
			if (header.getName().equalsIgnoreCase(name)) {
				return header.getValue();
			}
		}
		return null;
	}

	public boolean containsHeader(String name) {
		for (int i = 0; i < headers.size(); i++) {
			HttpHeader header = headers.get(i);
			if (header.getName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	public Iterator<HttpHeader> getHeaders(String name) {
		List<HttpHeader> list = new ArrayList<HttpHeader>();
		for (int i = 0; i < headers.size(); i++) {
			HttpHeader header = headers.get(i);
			if (header.getName().equalsIgnoreCase(name)) {
				list.add(header);
			}
		}
		return list.iterator();
	}

	public Iterator<HttpHeader> getAll() {
		return headers.iterator();
	}

	public void addHeader(String name, String value) {
		this.addHeader(new HttpHeader(name, value));
	}

	public void addHeader(HttpHeader header) {
		if (header == null)
			throw new NullPointerException("Header is null");
		this.headers.add(header);
	}

	public void setValue(String name, String value) {
		boolean found = false;
		for (int i = 0; i < headers.size(); i++) {
			HttpHeader header = headers.get(i);
			if (header.getName().equalsIgnoreCase(name)) {
				header.setValue(value);
				found = true;
			}
		}
		if (!found) {
			addHeader(name, value);
		}
	}

	public void add(String text) {
		addHeader(HttpHeader.parse(text));
	}

	public void clear() {
		this.headers.clear();
	}

	public void appendToBuffer(StringBuffer buf) {
		for (int i = 0; i < headers.size(); i++) {
			HttpHeader header = headers.get(i);
			buf.append(header.getName() + ": " + header.getValue() + "\r\n");
		}
	}

	public void loadFromStream(InputStream inStream) throws IOException {
		while (true) {
			String ln = NetUtils.readLine(inStream);
			if (ln.length() < 1)
				break;
			int index = ln.indexOf(":");
			if (index > 0) {
				String key = ln.substring(0, index).trim();
				String value = ln.substring(index + 1).trim();
				HttpHeader header = new HttpHeader(key, value);
				headers.add(header);
			}
		}
	}
}
