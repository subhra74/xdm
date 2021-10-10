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

package xdman.downloaders.ftp;

import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Segment;
import xdman.downloaders.SegmentDownloader;
import xdman.downloaders.metadata.HttpMetadata;

public class FtpDownloader extends SegmentDownloader {

	private final HttpMetadata metadata;

	public FtpDownloader(String id, String folder, HttpMetadata metadata) {
		super(id, folder);
		this.metadata = metadata;
	}

	@Override
	public AbstractChannel createChannel(Segment segment) {
		return new FtpChannel(segment, metadata.getUrl());
	}

	@Override
	public int getType() {
		return XDMConstants.FTP;
	}

	@Override
	public boolean isFileNameChanged() {
		return false;
		/*
		 * Logger.log("Checking for filename change " + (newFileName != null)); return
		 * newFileName != null;
		 */
	}

	@Override
	public String getNewFile() {
		return null;// newFileName;
	}

	@Override
	protected void chunkConfirmed(Segment c) {

	}

	@Override
	public HttpMetadata getMetadata() {
		return this.metadata;
	}

}
