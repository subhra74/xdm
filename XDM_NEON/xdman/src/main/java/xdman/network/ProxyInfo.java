package xdman.network;

public final class ProxyInfo {
	private String proxy = null;
	private int port = -1;
	private String socksProxy = null;
	private int socksPort = -1;

	public ProxyInfo(String paramString) {
		this(paramString, null);
	}

	public ProxyInfo(String paramString1, String paramString2) {
		int i;
		if (paramString1 != null) {
			i = paramString1.indexOf("//");
			if (i >= 0) {
				paramString1 = paramString1.substring(i + 2);
			}
			i = paramString1.lastIndexOf(':');
			if (i >= 0) {
				this.proxy = paramString1.substring(0, i);
				try {
					this.port = Integer.parseInt(paramString1.substring(i + 1).trim());
				} catch (Exception localException1) {
				}
			} else if (!paramString1.equals("")) {
				this.proxy = paramString1;
			}
		}
		if (paramString2 != null) {
			i = paramString2.lastIndexOf(':');
			if (i >= 0) {
				this.socksProxy = paramString2.substring(0, i);
				try {
					this.socksPort = Integer.parseInt(paramString2.substring(i + 1).trim());
				} catch (Exception localException2) {
				}
			} else if (!paramString2.equals("")) {
				this.socksProxy = paramString2;
			}
		}
	}

	public ProxyInfo(String paramString, int paramInt) {
		this(paramString, paramInt, null, -1);
	}

	public ProxyInfo(String paramString1, int paramInt1, String paramString2, int paramInt2) {
		this.proxy = paramString1;
		this.port = paramInt1;
		this.socksProxy = paramString2;
		this.socksPort = paramInt2;
	}

	public String getProxy() {
		return this.proxy;
	}

	public int getPort() {
		return this.port;
	}

	public String getSocksProxy() {
		return this.socksProxy;
	}

	public int getSocksPort() {
		return this.socksPort;
	}

	public boolean isProxyUsed() {
		return (this.proxy != null) || (this.socksProxy != null);
	}

	public boolean isSocksUsed() {
		return this.socksProxy != null;
	}

	public String toString() {
		if (this.proxy != null) {
			return this.proxy + ":" + this.port;
		}
		if (this.socksProxy != null) {
			return this.socksProxy + ":" + this.socksPort;
		}
		return "DIRECT";
	}

	public boolean isDirect() {
		if (this.proxy != null) {
			return false;
		}
		if (this.socksProxy != null) {
			return false;
		}
		return true;
	}
}
