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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.tinylog.Logger;

import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings("StringBufferMayBeStringBuilder")
public class VimeoHandler {

	public static boolean handle(File tempFile, ParsedHookData data) {
		try {
			StringBuffer buf = new StringBuffer();
			InputStream in = new FileInputStream(tempFile);
			BufferedReader r = new BufferedReader(new InputStreamReader(in));
			while (true) {
				String ln = r.readLine();
				if (ln == null) {
					break;
				}
				buf.append(ln).append("\n");
			}
			IOUtils.closeFlow(in);
			String keyword = "\"progressive\"";
			int index = buf.indexOf(keyword);
			if (index < 0) {
				return false;
			}
			index += keyword.length();
			index = buf.indexOf(":", index);
			if (index < 0) {
				return false;
			}
			index++;
			index = buf.indexOf("[", index);
			if (index < 0) {
				return false;
			}
			index++;
			int start = index;
			index = buf.indexOf("]", index);
			if (index < 0) {
				return false;
			}
			String str = buf.substring(start, index);
			index = 0;
			while (index != -1) {
				index = str.indexOf("{", index);
				if (index > -1) {
					index++;
					start = index;
					index = str.indexOf("}", index);
					if (index > -1) {
						String s = str.substring(start, index);
						processString(s, data);
					}
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return false;
	}

	private static void processString(String str, ParsedHookData data) {
		String quality = "", type = "", url = "";
		String[] arr = str.split(",");
		for (String s : arr) {
			int index = s.indexOf(":");
			if (index > 0) {
				String key = s.substring(0, index).replace("\"", "");
				String val = s.substring(index + 1).replace("\"", "");
				if (key.equals("url")) {
					url = val;
					Logger.info(url);
				}
				if (key.equals("quality")) {
					quality = val;
					Logger.info(quality);
				}
				if (key.equals("mime")) {
					type = val;
					Logger.info(type);
				}
			}
		}
		String ext = "mp4";
		if (type.contains("video/mp4")) {
			ext = "mp4";
		} else if (type.contains("video/webm")) {
			ext = "webm";
		}
		HttpMetadata metadata = new HttpMetadata();
		metadata.setUrl(url);
		metadata.setHeaders(data.getRequestHeaders());
		String file = data.getFile();
		if (StringUtils.isNullOrEmptyOrBlank(file)) {
			file = XDMUtils.getFileName(data.getUrl());
		}
		XDMApp.getInstance().addMedia(metadata, file + "." + ext, ext.toUpperCase() + " " + quality);
	}

}
