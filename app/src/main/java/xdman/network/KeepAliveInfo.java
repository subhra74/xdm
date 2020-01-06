package xdman.network;

import java.net.Socket;

public class KeepAliveInfo {
	private Socket socket;
	private String host;
	private int port;
	private long lastUsed;

	/**
	 * @return the socket
	 */
	public final Socket getSocket() {
		return socket;
	}

	/**
	 * @param socket
	 *            the socket to set
	 */
	public final void setSocket(Socket socket) {
		this.socket = socket;
	}

	/**
	 * @return the host
	 */
	public final String getHost() {
		return host;
	}

	/**
	 * @param host
	 *            the host to set
	 */
	public final void setHost(String host) {
		this.host = host;
	}

	/**
	 * @return the port
	 */
	public final int getPort() {
		return port;
	}

	/**
	 * @param port
	 *            the port to set
	 */
	public final void setPort(int port) {
		this.port = port;
	}

	/**
	 * @return the lastUsed
	 */
	public final long getLastUsed() {
		return lastUsed;
	}

	/**
	 * @param lastUsed
	 *            the lastUsed to set
	 */
	public final void setLastUsed(long lastUsed) {
		this.lastUsed = lastUsed;
	}
}
