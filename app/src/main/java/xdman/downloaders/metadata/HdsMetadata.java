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

public class HdsMetadata extends HttpMetadata {

	private int bitRate;

	public HdsMetadata() {
		super();
	}

	@Override
	public int getType() {
		return XDMConstants.HDS;
	}

	protected HdsMetadata(String id) {
		super(id);
	}

	@Override
	public HttpMetadata derive() {
		Logger.info("derive hds metadata");
		HdsMetadata md = new HdsMetadata();
		md.setHeaders(this.getHeaders());
		md.setUrl(this.getUrl());
		md.setBitRate(bitRate);
		return md;
	}

	public int getBitRate() {
		return bitRate;
	}

	public void setBitRate(int bitRate) {
		this.bitRate = bitRate;
	}

}
