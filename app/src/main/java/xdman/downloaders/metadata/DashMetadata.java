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

package xdman.downloaders.metadata;

import org.tinylog.Logger;

import xdman.XDMConstants;
import xdman.network.http.HeaderCollection;

public class DashMetadata extends HttpMetadata {

	private String url2;
	private long len1, len2;
	private HeaderCollection headers2;

	@Override
	public HttpMetadata derive() {
		Logger.info("derive dash metadata");
		DashMetadata md = new DashMetadata();
		md.setHeaders(this.getHeaders());
		md.setHeaders2(this.getHeaders2());
		md.setUrl(this.getUrl());
		md.setUrl2(this.getUrl2());
		md.setLen1(this.getLen1());
		md.setLen2(this.getLen2());
		return md;
	}

	public DashMetadata() {
		super();
		this.headers2 = new HeaderCollection();
	}

	protected DashMetadata(String id) {
		super(id);
		this.headers2 = new HeaderCollection();
	}

	@Override
	public int getType() {
		return XDMConstants.DASH;
	}

	public String getUrl2() {
		return url2;
	}

	public void setUrl2(String url2) {
		this.url2 = url2;
	}

	public void setHeaders2(HeaderCollection headers2) {
		this.headers2 = headers2;
	}

	public HeaderCollection getHeaders2() {
		return this.headers2;
	}

	public long getLen1() {
		return len1;
	}

	public void setLen1(long len1) {
		this.len1 = len1;
	}

	public long getLen2() {
		return len2;
	}

	public void setLen2(long len2) {
		this.len2 = len2;
	}

}
