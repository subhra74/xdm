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

package xdman.util;

import java.io.File;
import java.io.InputStream;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tinylog.Logger;

import xdman.Config;
import xdman.XDMApp;
import xdman.network.http.JavaHttpClient;

@SuppressWarnings("unused")
public class UpdateChecker {

	public static final int APP_UPDATE_AVAILABLE = 10, COMP_UPDATE_AVAILABLE = 20, COMP_NOT_INSTALLED = 30,
			NO_UPDATE_AVAILABLE = 40;

	private static final Pattern PATTERN_TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"(\\d+\\.\\d+\\.\\d+)\"");

	public static int getUpdateStat() {
		Logger.info("checking for app update");
		if (isAppUpdateAvailable())
			return APP_UPDATE_AVAILABLE;
		return NO_UPDATE_AVAILABLE;
	}

	private static boolean isAppUpdateAvailable() {
		return isUpdateAvailable(XDMApp.APP_VERSION);
	}

	private static int isComponentUpdateAvailable() {
		String componentVersion = getComponentVersion();
		Logger.info("current component version: " + componentVersion);
		if (componentVersion == null)
			return -1;
		return isUpdateAvailable(componentVersion) ? 0 : 1;
	}

	public static String getComponentVersion() {
		File f = new File(Config.getInstance().getDataFolder());
		String[] files = f.list((dir, name) -> name.endsWith(".version"));
		if (files == null || files.length < 1) {
			Logger.warn("Component not installed");
			Logger.warn("Checking fallback components");
			return getFallbackComponentVersion();
		}
		return files[0].split("\\.")[0];
	}

	public static String getFallbackComponentVersion() {
		File f = Objects.requireNonNull(XDMUtils.getJarFile()).getParentFile();
		String[] files = f.list((dir, name) -> name.endsWith(".version"));
		if (files == null || files.length < 1) {
			Logger.warn("Component not installed");
			return null;
		}

		return files[0].split("\\.")[0];
	}

	private static boolean isUpdateAvailable(String version) {
		JavaHttpClient client = null;
		try {
			client = new JavaHttpClient(XDMApp.APP_UPDATE_URL + "?ver=" + version);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.info("manifest download response: " + resp);
			if (resp == 200) {
				InputStream in = client.getInputStream();
				StringBuilder sb = new StringBuilder();
				while (true) {
					int x = in.read();
					if (x == -1)
						break;
					sb.append((char) x);
				}
				return isNewerVersion(sb, XDMApp.APP_VERSION);
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			if (client != null) {
				try {
					client.dispose();
				} catch (Exception e) {
					Logger.error(e);
				}
			}
		}
		return false;
	}

	private static boolean isNewerVersion(StringBuilder text, String v2) {
		try {
			Matcher matcher = PATTERN_TAG.matcher(text);
			if (matcher.find()) {
				String v1 = matcher.group(1);
				Logger.info(v1 + " " + v2);
				if (v1.indexOf(".") > 0 && v2.indexOf(".") > 0) {
					String[] arr1 = v1.split("\\.");
					String[] arr2 = v2.split("\\.");
					for (int i = 0; i < Math.min(arr1.length, arr2.length); i++) {
						int ia = Integer.parseInt(arr1[i]);
						int ib = Integer.parseInt(arr2[i]);
						if (ia > ib) {
							return true;
						}
					}
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return false;
	}
}
