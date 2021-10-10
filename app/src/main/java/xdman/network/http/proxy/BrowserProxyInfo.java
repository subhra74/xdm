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

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public final class BrowserProxyInfo {

	private int type = 0;
	private String _httpHost;
	private int _httpPort = -1;
	private String _httpsHost;
	private int _httpsPort = -1;
	private String _ftpHost;
	private int _ftpPort = -1;
	private String _gopherHost;
	private int _gopherPort = -1;
	private String _socksHost;
	private int _socksPort = -1;
	private String[] _overrides = null;
	private String _autoConfigURL;
	private boolean _hintOnly;
	private boolean _isWPADEnabled;

	public int getType() {
		return this.type;
	}

	public void setType(int paramInt) {
		this.type = paramInt;
	}

	public String getHttpHost() {
		return this._httpHost;
	}

	public void setHttpHost(String paramString) {
		this._httpHost = paramString;
	}

	public int getHttpPort() {
		return this._httpPort;
	}

	public void setHttpPort(int paramInt) {
		this._httpPort = paramInt;
	}

	public String getHttpsHost() {
		return this._httpsHost;
	}

	public void setHttpsHost(String paramString) {
		this._httpsHost = paramString;
	}

	public int getHttpsPort() {
		return this._httpsPort;
	}

	public void setHttpsPort(int paramInt) {
		this._httpsPort = paramInt;
	}

	public String getFtpHost() {
		return this._ftpHost;
	}

	public void setFtpHost(String paramString) {
		this._ftpHost = paramString;
	}

	public int getFtpPort() {
		return this._ftpPort;
	}

	public void setFtpPort(int paramInt) {
		this._ftpPort = paramInt;
	}

	public String getGopherHost() {
		return this._gopherHost;
	}

	public void setGopherHost(String paramString) {
		this._gopherHost = paramString;
	}

	public int getGopherPort() {
		return this._gopherPort;
	}

	public void setGopherPort(int paramInt) {
		this._gopherPort = paramInt;
	}

	public String getSocksHost() {
		return this._socksHost;
	}

	public void setSocksHost(String paramString) {
		this._socksHost = paramString;
	}

	public int getSocksPort() {
		return this._socksPort;
	}

	public void setSocksPort(int paramInt) {
		this._socksPort = paramInt;
	}

	public String[] getOverrides() {
		return this._overrides;
	}

	public String getOverridesString() {
		String str = "";
		if ((this._overrides != null) && (this._overrides.length > 0)) {
			for (int i = 0; i < this._overrides.length; i++) {
				if (i != this._overrides.length - 1) {
					str = str.concat(this._overrides[i] + "|");
				} else {
					str = str.concat(this._overrides[i]);
				}
			}
		}
		return str;
	}

	public void setOverrides(String[] paramArrayOfString) {
		this._overrides = paramArrayOfString;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void setOverrides(List paramList) {
		if (paramList != null) {

			ArrayList localArrayList = new ArrayList(paramList);
			this._overrides = new String[localArrayList.size()];
			this._overrides = ((String[]) localArrayList.toArray(this._overrides));
		}
	}

	public String getAutoConfigURL() {
		return this._autoConfigURL;
	}

	public void setAutoConfigURL(String paramString) {
		this._autoConfigURL = paramString;
	}

	public void setHintOnly(boolean paramBoolean) {
		this._hintOnly = paramBoolean;
	}

	public boolean isAutoProxyDetectionEnabled() {
		return this._isWPADEnabled;
	}

	public void setAutoProxyDetectionEnabled(boolean paramBoolean) {
		this._isWPADEnabled = paramBoolean;
	}

	public boolean isHintOnly() {
		return this._hintOnly;
	}

}
