package xdman.network.http;

import java.io.IOException;
import java.io.InputStream;

import xdman.network.ICredentialManager;

public abstract class HttpClient {
	protected HeaderCollection requestHeaders;
	protected HeaderCollection responseHeaders;
	protected ICredentialManager credentialMgr;
	protected int statusCode;
	protected String statusMessage;

	protected HttpClient() {
		this.requestHeaders = new HeaderCollection();
		this.responseHeaders = new HeaderCollection();
	}

	public void addHeader(String name, String value) {
		this.requestHeaders.addHeader(name, value);
	}

	public void setHeader(String name, String value) {
		this.requestHeaders.setValue(name, value);
	}

	public int getStatusCode() {
		return this.statusCode;
	}

	public String getStatusMessage() {
		return this.statusMessage;
	}

	public String getResponseHeader(String name) {
		return this.responseHeaders.getValue(name);
	}

	public void setCredentialMgr(ICredentialManager mgr) {
		this.credentialMgr = mgr;
	}

	public abstract void connect() throws IOException;

	public abstract void dispose();

	public abstract InputStream getInputStream() throws IOException;

	public abstract long getContentLength() throws IOException;

	public abstract String getHost();
}
