package xdman.network.http;

public class WebProxy {
	public WebProxy(String host, int port) {
		super();
		this.host = host;
		this.port = port;
	}

	private String host;
	private int port;
	private boolean socks;

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public boolean isSocks() {
		return socks;
	}

	public void setSocks(boolean socks) {
		this.socks = socks;
	}
}
