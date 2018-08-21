package xdman.network.http;

import xdman.network.*;
import xdman.util.Logger;
import xdman.util.NetUtils;
import xdman.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class XDMHttpClient extends HttpClient {
	private ParsedURL _url;
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

		}
		try {
			this.socket.close();
		} catch (Exception e) {

		}
	}

	@Override
	public InputStream getInputStream() {
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
				Logger.log("Creating new socket");
				this.socket = createSocket();
			} else {
				reusing = true;
				Logger.log("Reusing existing socket");
				this.socket = sock;
			}
			OutputStream sockOut = socket.getOutputStream();
			InputStream sockIn = socket.getInputStream();
			String reqLine = "GET " + _url.getPathAndQuery() + " HTTP/1.1";
			StringBuffer reqBuf = new StringBuffer();
			reqBuf.append(reqLine + "\r\n");
			requestHeaders.appendToBuffer(reqBuf);
			reqBuf.append("\r\n");

			Logger.log("Sending request:\n" + reqBuf);

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

			Logger.log(statusLine);

			responseHeaders.loadFromStream(sockIn);
			length = NetUtils.getContentLength(responseHeaders);
			StringBuffer b2 = new StringBuffer();
			responseHeaders.appendToBuffer(b2);
			Logger.log(b2);
			
			in = new FixedRangeInputStream(NetUtils.getInputStream(responseHeaders, socket.getInputStream()), length);

			if (reusing) {
				Logger.log("Socket reuse successful");
			}
			

			// if (statusCode == 401 || statusCode == 407) {
			// throw new JavaClientRequiredException();
			// }

			keepAliveSupported = !"close".equals(responseHeaders.getValue("connection"));

		} catch (Exception e) {
			String msg = String.format("Unable to connect to server: %s",
					e.getMessage());
			Logger.log(msg,
					e);
			throw new NetworkException(msg);
		}
	}

	private void releaseSocket() {
		Logger.log("Releasing socket for reuse");
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
	public long getContentLength() {
		return length;
	}

	@Override
	public String getHost() {
		return _url.getHost() + ":" + _url.getPort();
	}
}
