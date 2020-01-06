package xdman.util;

import java.io.*;
import java.util.zip.GZIPInputStream;

import xdman.network.http.*;

public class NetUtils {
	public static byte[] getBytes(String str) {
		return str.getBytes();
	}

	public static final String readLine(InputStream in) throws IOException {
		StringBuffer buf = new StringBuffer();
		while (true) {
			int x = in.read();
			if (x == -1)
				throw new IOException("Unexpected EOF while reading header line");
			if (x == '\n')
				return buf.toString();
			if (x != '\r')
				buf.append((char) x);
		}
	}

	public static final int getResponseCode(String statusLine) {
		String arr[] = statusLine.split(" ");
		if (arr.length < 2)
			return 400;
		return Integer.parseInt(arr[1]);
	}

	public static long getContentLength(HeaderCollection headers) {
		try {
			String clen = headers.getValue("content-length");
			if (clen != null) {
				return Long.parseLong(clen);
			} else {
				clen = headers.getValue("content-range");
				if (clen != null) {
					String str = clen.split(" ")[1];
					str = str.split("/")[0];
					String arr[] = str.split("-");
					return Long.parseLong(arr[1]) - Long.parseLong(arr[0]) + 1;
				} else {
					return -1;
				}
			}
		} catch (Exception e) {
			return -1;
		}
	}

	public static InputStream getInputStream(HeaderCollection respHeaders, InputStream inStream) throws IOException {
		String transferEncoding = respHeaders.getValue("transfer-encoding");
		if (!StringUtils.isNullOrEmptyOrBlank(transferEncoding)) {
			inStream = new ChunkedInputStream(inStream);
		}
		String contentEncoding = respHeaders.getValue("content-encoding");
		Logger.log("Content-Encoding: " + contentEncoding);
		if (!StringUtils.isNullOrEmptyOrBlank(contentEncoding)) {
			if (contentEncoding.equalsIgnoreCase("gzip")) {
				inStream = new GZIPInputStream(inStream);
			} else if (!(contentEncoding.equalsIgnoreCase("none") || contentEncoding.equalsIgnoreCase("identity"))) {
				throw new IOException("Content Encoding not supported: " + contentEncoding);
			}
		}
		return inStream;
	}

	public static void skipRemainingStream(HeaderCollection respHeaders, InputStream inStream) throws IOException {
		inStream = getInputStream(respHeaders, inStream);
		long length = getContentLength(respHeaders);
		skipRemainingStream(inStream, length);
	}

	public static void skipRemainingStream(InputStream inStream, long length) throws IOException {
		byte buf[] = new byte[8192];
		if (length > 0) {
			while (length > 0) {
				int r = (int) (length > buf.length ? buf.length : length);
				int x = inStream.read(buf, 0, r);
				if (x == -1)
					break;
				length -= x;
			}
		} else {
			while (true) {
				int x = inStream.read(buf);
				if (x == -1)
					break;
			}
		}
	}

	public static String getNameFromContentDisposition(String header) {
		try {
			if (header == null)
				return null;
			String headerLow = header.toLowerCase();
			if (headerLow.startsWith("attachment") || headerLow.startsWith("inline")) {
				String arr[] = header.split(";");
				for (int i = 0; i < arr.length; i++) {
					String str = arr[i].trim();
					if (str.toLowerCase().startsWith("filename")) {
						int index = str.indexOf('=');
						String file = str.substring(index + 1).replace("\"", "").trim();
						try {
							return XDMUtils.decodeFileName(file);
						} catch (Exception e) {
							return file;
						}

					}
				}
			}
		} catch (Exception e) {
		}
		return null;
	}

	public static String getCleanContentType(String contentType) {
		if (contentType == null || contentType.length() < 1)
			return contentType;
		int index = contentType.indexOf(";");
		if (index > 0) {
			contentType = contentType.substring(0, index).trim().toLowerCase();
		}
		return contentType;
	}
}
