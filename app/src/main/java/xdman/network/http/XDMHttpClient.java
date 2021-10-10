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
import java.io.OutputStream;
import java.net.Socket;

import org.tinylog.Logger;

import xdman.network.FixedRangeInputStream;
import xdman.network.HostUnreachableException;
import xdman.network.KeepAliveConnectionCache;
import xdman.network.NetworkException;
import xdman.network.ParsedURL;
import xdman.network.SocketFactory;
import xdman.util.IOUtils;
import xdman.util.NetUtils;
import xdman.util.StringUtils;

@SuppressWarnings("FieldCanBeLocal")
public class XDMHttpClient extends HttpClient {

	private final ParsedURL _url;
	private Socket socket;
	private String statusLine;
	private long length;
	private FixedRangeInputStream in;
	private boolean keepAliveSupported;
	private boolean closed;

	public XDMHttpClient(String url) {
		super();
		this._url = ParsedURL.parse(url);
		this.length = -1;
	}

	public boolean isFinished() {
		try {
			return (in.isStreamFinished() && keepAliveSupported);
		} catch (Exception e) {
			Logger.error(e);
		}
		return false;
	}

	@Override
	public void dispose() {
		if (closed)
			return;
		closed = true;
		try {
			if (in.isStreamFinished() && keepAliveSupported) {
				releaseSocket();
				return;
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		IOUtils.closeFlow(this.socket);
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return in;
	}

	@Override
	public void connect() throws IOException {
		try {
			int port = _url.getPort();
			String portStr = (port == 80 || port == 443) ? "" : ":" + port;
			requestHeaders.setValue("host", _url.getHost() + portStr);
			Socket sock = KeepAliveConnectionCache.getInstance().getReusableSocket(_url.getHost(), _url.getPort());
			boolean reusing = false;
			if (sock == null) {
				Logger.info("Creating new socket");
				this.socket = createSocket();
			} else {
				reusing = true;
				Logger.info("Reusing existing socket");
				this.socket = sock;
			}
			OutputStream sockOut = socket.getOutputStream();
			InputStream sockIn = socket.getInputStream();
			String reqLine = "GET " + _url.getPathAndQuery() + " HTTP/1.1";
			StringBuffer reqBuf = new StringBuffer();
			reqBuf.append(reqLine).append("\r\n");
			requestHeaders.appendToBuffer(reqBuf);
			reqBuf.append("\r\n");

			Logger.info("Sending request:\n" + reqBuf);

			sockOut.write(StringUtils.getBytes(reqBuf));
			sockOut.flush();
			statusLine = NetUtils.readLine(sockIn);

			String[] arr = statusLine.split(" ");
			this.statusCode = Integer.parseInt(arr[1].trim());
			if (arr.length > 2) {
				this.statusMessage = arr[2].trim();
			} else {
				this.statusMessage = "";
			}

			Logger.info(statusLine);

			responseHeaders.loadFromStream(sockIn);
			length = NetUtils.getContentLength(responseHeaders);
			StringBuffer b2 = new StringBuffer();
			responseHeaders.appendToBuffer(b2);
			Logger.info(b2);

			in = new FixedRangeInputStream(NetUtils.getInputStream(responseHeaders, socket.getInputStream()), length);

			if (reusing) {
				Logger.info("Socket reuse successful");
			}

			keepAliveSupported = !"close".equals(responseHeaders.getValue("connection"));

		} catch (HostUnreachableException e) {
			Logger.error(e);
			throw new NetworkException("Unable to connect to server");
		} catch (Exception e) {
			Logger.error(e);
			throw new NetworkException(e.getMessage());
		}
	}

	private void releaseSocket() {
		Logger.info("Releasing socket for reuse");
		KeepAliveConnectionCache.getInstance().putSocket(socket, _url.getHost(), _url.getPort());
	}

	private Socket createSocket() throws IOException {
		Socket socket = SocketFactory.createSocket(_url.getHost(), _url.getPort());
		if (_url.getProtocol().equalsIgnoreCase("https")) {
			socket = SocketFactory.wrapSSL(socket, _url.getHost(), _url.getPort());
		}
		return socket;
	}

	@Override
	public long getContentLength() throws IOException {
		return length;
	}

	@Override
	public String getHost() {
		return _url.getHost() + ":" + _url.getPort();
	}

}
