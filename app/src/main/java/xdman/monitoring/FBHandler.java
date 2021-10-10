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
import java.util.ArrayList;

import org.tinylog.Logger;

import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class FBHandler {

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
			Logger.info("Parsing facebook page...");
			ArrayList<String> sdUrls1 = findURL("sd_src", buf);
			ArrayList<String> sdUrls2 = findURL("sd_src_no_ratelimit", buf);
			ArrayList<String> hdUrls1 = findURL("hd_src", buf);
			ArrayList<String> hdUrls2 = findURL("hd_src_no_ratelimit", buf);
			for (String s : sdUrls1) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(s);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 LOW");
			}
			for (String s : sdUrls2) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(s);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 MEDIUM");
			}
			for (String s : hdUrls1) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(s);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 HD");
			}
			for (String s : hdUrls2) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(s);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 HQ");
			}
			return true;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}
	}

	private static ArrayList<String> findURL(String keyword, StringBuffer buf) {
		int index = 0;
		ArrayList<String> urlList = new ArrayList<>();
		while (true) {
			index = buf.indexOf(keyword, index);
			if (index < 0)
				break;
			index += keyword.length();
			index = buf.indexOf(":", index);
			if (index < 0) {
				break;
			}
			index += 1;

			while (true) {
				char ch = buf.charAt(index);
				if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t')
					index++;
				else
					break;
			}

			char ch = buf.charAt(index);
			if (ch == '"') {
				index++;
				int index3 = buf.indexOf("\"", index);
				String url = decodeJSONEscape(buf.substring(index, index3).trim().replace("\"", ""));
				Logger.info(keyword + ": " + url);
				urlList.add(url);
			}
		}
		return urlList;
	}

	private static String decodeJSONEscape(String json) {
		StringBuilder buf = new StringBuilder();
		int pos = 0;
		while (true) {
			int index = json.indexOf("\\u", pos);
			if (index < 0) {
				if (pos < json.length()) {
					buf.append(json.substring(pos));
				}
				break;
			}
			buf.append(json, pos, index);
			pos = index;
			String code = json.substring(pos + 2, pos + 2 + 4);
			int char_code = Integer.parseInt(code, 16);
			buf.append((char) char_code);
			pos += 6;
		}
		return buf.toString().replace("\\", "");
	}

}
