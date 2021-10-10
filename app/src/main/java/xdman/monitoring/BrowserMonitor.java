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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.tinylog.Logger;

import xdman.Config;
import xdman.XDMApp;
import xdman.ui.components.VideoPopupItem;
import xdman.util.Base64;
import xdman.util.IOUtils;

@SuppressWarnings("FieldCanBeLocal")
public class BrowserMonitor implements Runnable {

	private static BrowserMonitor _this;
	private FileChannel fc;
	private FileLock fileLock;

	public static BrowserMonitor getInstance() {
		if (_this == null) {
			_this = new BrowserMonitor();
		}
		return _this;
	}

	public void startMonitoring() {
		updateSettingsAndStatus();
		Thread t = new Thread(this);
		t.start();
	}

	public void updateSettingsAndStatus() {
		try {
			Path homePath = Paths.get(System.getProperty("user.home"), ".xdman");
			if (!Files.exists(homePath)) {
				Files.createDirectories(homePath);
			}
			Files.writeString(Paths.get(System.getProperty("user.home"), ".xdman", "settings.json"), getSync());
			Files.writeString(Paths.get(System.getProperty("user.home"), ".xdman", "settings_updated"), "");
		} catch (IOException e) {
			Logger.error(e);
		}
	}

	private static void appendArray(String[] arr, StringBuilder buf) {
		boolean insertComma = false;
		if (arr != null && arr.length > 0) {
			for (String s : arr) {
				if (insertComma) {
					buf.append(",");
				} else {
					insertComma = true;
				}
				buf.append("\"").append(s).append("\"");
			}
		}
	}

	public static String getSyncJSON() {
		StringBuilder json = new StringBuilder();
		json.append("{\n\"enabled\": ");
		json.append(Config.getInstance().isBrowserMonitoringEnabled());
		json.append(",\n\"blockedHosts\": [");
		appendArray(Config.getInstance().getBlockedHosts(), json);// json.append(String.join(",",
		json.append("],");
		json.append("\n\"videoUrls\": [");
		appendArray(Config.getInstance().getVidUrls(), json);// json.append(String.join(",",
		json.append("],");
		json.append("\n\"fileExts\": [");
		appendArray(Config.getInstance().getFileExits(), json);
		json.append("],");
		json.append("\n\"vidExts\": [");
		appendArray(Config.getInstance().getVidExits(), json);
		json.append("],");
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
			String id = item.getMetadata().getId();
			String text = encode(item.getFile());
			String info = item.getInfo();
			if (count > 0)
				sb.append(",");
			sb.append(String.format("{\"id\": \"%s\", \"text\": \"%s\",\"info\":\"%s\"}", id, text, info));
			count++;
		}
		json.append("\n\"vidList\": [");
		json.append(sb);
		json.append("],");
		String mimeTypes = "\n\"mimeList\": [\"video/\",\"audio/\",\"mpegurl\",\"f4m\",\"m3u8\"]";
		json.append(mimeTypes);
		json.append("\n}");
		return json.toString();
	}

	public static String getSync() {

		StringBuilder json = new StringBuilder();
		try {
			json.append("enabled:").append(Config.getInstance().isBrowserMonitoringEnabled()).append("\n");
			json.append("blockedHosts:").append(String.join(",", Config.getInstance().getBlockedHosts())).append("\n");
			json.append("videoUrls:").append(String.join(",", Config.getInstance().getVidUrls())).append("\n");
			json.append("fileExts:").append(String.join(",", Config.getInstance().getFileExits())).append("\n");
			json.append("vidExts:").append(String.join(",", Config.getInstance().getVidExits())).append("\n");
			json.append("mimeList:").append(String.join(",", Config.getInstance().getVidMime())).append("\n");

			List<String> videoPopupItems = new ArrayList<>();
			for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
				String id = item.getMetadata().getId();
				String text = item.getFile();
				String info = item.getInfo();
				videoPopupItems.add(String.join("|", Base64.encode(id.getBytes(StandardCharsets.UTF_8)),
						Base64.encode(text.getBytes(StandardCharsets.UTF_8)), Base64.encode(info.getBytes(StandardCharsets.UTF_8))));
			}
			json.append("vidList:").append(String.join(",", videoPopupItems)).append("\n");
		} catch (Exception e) {
			Logger.error(e);
		}

		return json.toString();
	}

	private static String encode(String str) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (char ch : str.toCharArray()) {
			if (count > 0)
				sb.append(",");
			sb.append((int) ch);
			count++;
		}
		return sb.toString();
	}

	public void run() {
		ServerSocket serverSock = null;
		try {
			serverSock = new ServerSocket();
			serverSock.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 9614));
			XDMApp.instanceStarted();
			acquireGlobalLock();
			while (true) {
				Socket sock = serverSock.accept();
				MonitoringSession session = new MonitoringSession(sock);
				session.start();
			}
		} catch (Exception e) {
			Logger.error(e);
			XDMApp.instanceAlreadyRunning();
		}
		IOUtils.closeFlow(serverSock);
	}

	private void acquireGlobalLock() {
		try {
			fc = FileChannel.open(Paths.get(System.getProperty("user.home"), XDMApp.GLOBAL_LOCK_FILE),
					EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ,
							StandardOpenOption.WRITE));
			int maxRetry = 10;
			for (int i = 0; i < maxRetry; i++) {
				fileLock = fc.tryLock();
				if (fileLock != null) {
					Logger.info("Lock acquired...");
					return;
				}

				Thread.sleep(500);
			}
		} catch (Exception e) {
			Logger.error(e);
		}

	}

}
