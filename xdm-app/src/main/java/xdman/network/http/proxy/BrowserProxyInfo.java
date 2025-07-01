package xdman.network.http.proxy;

import java.util.ArrayList;
import java.util.List;

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

//  public String toString()
//  {
//    StringBuffer localStringBuffer = new StringBuffer();
//    localStringBuffer.append(ResourceManager.getMessage("net.proxy.configuration.text"));
//    switch (this.type)
//    {
//    case 3: 
//      localStringBuffer.append(ResourceManager.getMessage("net.proxy.type.browser"));
//      break;
//    case 2: 
//      localStringBuffer.append(ResourceManager.getMessage("net.proxy.type.auto"));
//      localStringBuffer.append("\n");
//      localStringBuffer.append("     URL: " + this._autoConfigURL);
//      break;
//    case 1: 
//      localStringBuffer.append(ResourceManager.getMessage("net.proxy.type.manual"));
//      localStringBuffer.append("\n");
//      localStringBuffer.append("     " + ResourceManager.getMessage("net.proxy.text"));
//      if (this._httpHost != null)
//      {
//        localStringBuffer.append("http=" + this._httpHost);
//        if (this._httpPort != -1) {
//          localStringBuffer.append(":" + this._httpPort);
//        }
//      }
//      if (this._httpsHost != null)
//      {
//        localStringBuffer.append(",https=" + this._httpsHost);
//        if (this._httpsPort != -1) {
//          localStringBuffer.append(":" + this._httpsPort);
//        }
//      }
//      if (this._ftpHost != null)
//      {
//        localStringBuffer.append(",ftp=" + this._ftpHost);
//        if (this._ftpPort != -1) {
//          localStringBuffer.append(":" + this._ftpPort);
//        }
//      }
//      if (this._gopherHost != null)
//      {
//        localStringBuffer.append(",gopher=" + this._gopherHost);
//        if (this._gopherPort != -1) {
//          localStringBuffer.append(":" + this._gopherPort);
//        }
//      }
//      if (this._socksHost != null)
//      {
//        localStringBuffer.append(",socks=" + this._socksHost);
//        if (this._socksPort != -1) {
//          localStringBuffer.append(":" + this._socksPort);
//        }
//      }
//      localStringBuffer.append("\n");
//      localStringBuffer.append("     " + ResourceManager.getMessage("net.proxy.override.text"));
//      if (this._overrides != null)
//      {
//        int i = 1;
//        for (int j = 0; j < this._overrides.length; j++)
//        {
//          if (j != 0) {
//            localStringBuffer.append(",");
//          }
//          localStringBuffer.append(this._overrides[j]);
//        }
//      }
//      break;
//    case 0: 
//      localStringBuffer.append(ResourceManager.getMessage("net.proxy.type.none"));
//      break;
//    case 4: 
//      localStringBuffer.append(ResourceManager.getMessage("net.proxy.type.system"));
//      break;
//    default: 
//      localStringBuffer.append("<Unrecognized Proxy Type>");
//    }
//    return localStringBuffer.toString();
//  }
}
