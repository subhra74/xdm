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

import xdman.network.ICredentialManager;

@SuppressWarnings("unused")
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
