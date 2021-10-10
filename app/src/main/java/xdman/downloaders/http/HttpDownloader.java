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

package xdman.downloaders.http;

import org.tinylog.Logger;

import xdman.XDMConstants;
import xdman.downloaders.AbstractChannel;
import xdman.downloaders.Segment;
import xdman.downloaders.SegmentDownloader;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.MimeUtil;
import xdman.util.NetUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class HttpDownloader extends SegmentDownloader {

	private final HttpMetadata metadata;
	private String newFileName;
	private boolean isJavaClientRequired;

	public HttpDownloader(String id, String folder, HttpMetadata metadata) {
		super(id, folder);
		this.metadata = metadata;
	}

	@Override
	public AbstractChannel createChannel(Segment segment) {
		StringBuffer buf = new StringBuffer();
		metadata.getHeaders().appendToBuffer(buf);
		Logger.info("Headers all: " + buf);
		return new HttpChannel(segment, metadata.getUrl(), metadata.getHeaders(), length,
				isJavaClientRequired);
	}

	@Override
	public int getType() {
		return XDMConstants.HTTP;
	}

	@Override
	public boolean isFileNameChanged() {
		Logger.info("Checking for filename change " + (newFileName != null));
		return newFileName != null;
	}

	@Override
	public String getNewFile() {
		return newFileName;
	}

	@Override
	protected void chunkConfirmed(Segment c) {
		String oldFileName = getOutputFileName(false);
		HttpChannel hc = (HttpChannel) c.getChannel();
		this.isJavaClientRequired = hc.isJavaClientRequired();
		super.getLastModifiedDate(c);
		if (hc.isRedirected()) {
			metadata.setUrl(hc.getRedirectUrl());
			metadata.save();

		}

		if ((hc.getHeader("content-type") + "").contains("text/html")) {
			if (hc.getHeader("content-disposition") == null) {
				newFileName = XDMUtils.getFileNameWithoutExtension(oldFileName) + ".html";
				outputFormat = 0;
			}
		}

		boolean nameSet = false;
		String contentDispositionHeader = hc.getHeader("content-disposition");
		if (contentDispositionHeader != null) {
			if (outputFormat == 0) {
				Logger.info("checking content disposition");
				String name = NetUtils.getNameFromContentDisposition(contentDispositionHeader);
				if (name != null) {
					this.newFileName = name;
					nameSet = true;
					Logger.info("set new filename: " + newFileName);
				}
			}
		}
		if (!nameSet) {
			String ext = XDMUtils.getExtension(oldFileName);
			if (StringUtils.isNullOrEmptyOrBlank(ext)) {
				String newExt = MimeUtil.getFileExt(hc.getHeader("content-type"));
				if (newExt != null) {
					newFileName = oldFileName + "." + newExt;
				}
			}
			Logger.info("new filename: " + newFileName);
		}
	}

	@Override
	public HttpMetadata getMetadata() {
		return this.metadata;
	}

}
