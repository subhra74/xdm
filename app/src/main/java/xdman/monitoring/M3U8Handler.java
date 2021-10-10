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

package xdman.monitoring;

import java.io.File;
import java.util.List;

import org.tinylog.Logger;

import xdman.XDMApp;
import xdman.downloaders.hls.HlsPlaylist;
import xdman.downloaders.hls.HlsPlaylistItem;
import xdman.downloaders.hls.PlaylistParser;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class M3U8Handler {

	public static boolean handle(File m3u8file, ParsedHookData data) {
		try {
			Logger.info("Handing manifest: ...");
			HlsPlaylist playlist = PlaylistParser.parse(m3u8file.getAbsolutePath(), data.getUrl());
			if (playlist == null) {
				Logger.info("Playlist empty");
				return true;
			}

			if (!playlist.isMaster()) {
				if (playlist.getItems() != null && playlist.getItems().size() > 0) {
					HlsMetadata metadata = new HlsMetadata();
					metadata.setUrl(data.getUrl());
					metadata.setHeaders(data.getRequestHeaders());
					String file = data.getFile();
					if (StringUtils.isNullOrEmptyOrBlank(file)) {
						file = XDMUtils.getFileName(data.getUrl());
					}
					Logger.info("adding media");
					XDMApp.getInstance().addMedia(metadata, file + ".ts", "HLS");
				}
			} else {
				List<HlsPlaylistItem> items = playlist.getItems();
				if (items != null) {
					for (HlsPlaylistItem item : items) {
						String url = item.getUrl();
						HlsMetadata metadata = new HlsMetadata();
						metadata.setUrl(url);
						metadata.setHeaders(data.getRequestHeaders());
						String file = data.getFile();
						if (StringUtils.isNullOrEmptyOrBlank(file)) {
							file = XDMUtils.getFileName(data.getUrl());
						}
						StringBuilder infoStr = new StringBuilder();
						if (!StringUtils.isNullOrEmptyOrBlank(item.getBandwidth())) {
							infoStr.append(item.getBandwidth());
						}
						if (infoStr.length() > 0) {
							infoStr.append(" ");
						}
						if (!StringUtils.isNullOrEmptyOrBlank(item.getResolution())) {
							infoStr.append(item.getResolution());
						}
						Logger.info("adding media");
						XDMApp.getInstance().addMedia(metadata, file + ".ts", infoStr.toString());
					}
				}
			}
			return true;
		} catch (Exception e) {
			Logger.error(e);
		}
		return false;
	}

}
