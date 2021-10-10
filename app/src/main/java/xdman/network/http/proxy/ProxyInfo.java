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

package xdman.network.http.proxy;

import org.tinylog.Logger;

@SuppressWarnings("unused")
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
				} catch (Exception ex) {
					Logger.error(ex);
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
				} catch (Exception ex) {
					Logger.error(ex);
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

}
