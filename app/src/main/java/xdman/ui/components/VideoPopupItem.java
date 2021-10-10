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

package xdman.ui.components;

import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.StringUtils;

public class VideoPopupItem {
	private HttpMetadata metadata;
	private String file;
	private String info;
	private long timestamp;

	public final HttpMetadata getMetadata() {
		return metadata;
	}

	public final void setMetadata(HttpMetadata metadata) {
		this.metadata = metadata;
	}

	public final String getFile() {
		return file;
	}

	public final void setFile(String file) {
		this.file = file;
	}

	public final String getInfo() {
		return info;
	}

	public final void setInfo(String info) {
		this.info = info;
	}

	public final long getTimestamp() {
		return timestamp;
	}

	public final void setTimestamp(long timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public String toString() {
		if (StringUtils.isNullOrEmptyOrBlank(file)) {
			return "";
		}

		return (StringUtils.isNullOrEmptyOrBlank(info) ? "" : "[ " + info + " ]  ")
				+ (file.length() > 30 ? file.substring(0, 25) + "..." : file);
	}
}
