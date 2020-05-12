package xdman.monitoring;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import xdman.Config;
import xdman.XDMApp;
import xdman.ui.components.VideoPopupItem;
import xdman.util.Base64;
import xdman.util.Logger;

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
			e.printStackTrace();
		}
	}

	private static void appendArray(String[] arr, StringBuilder buf) {
		boolean insertComma = false;
		if (arr != null && arr.length > 0) {
			for (int i = 0; i < arr.length; i++) {
				if (insertComma) {
					buf.append(",");
				} else {
					insertComma = true;
				}
				buf.append("\"" + arr[i] + "\"");
			}
		}
	}
	
	public static String getSyncJSON() {
		StringBuilder json = new StringBuilder();
		json.append("{\n\"enabled\": ");
		json.append(Config.getInstance().isBrowserMonitoringEnabled());
		json.append(",\n\"blockedHosts\": [");
		appendArray(Config.getInstance().getBlockedHosts(), json);// json.append(String.join(",",
																	// Config.getInstance().getBlockedHosts()));
		json.append("],");
		json.append("\n\"videoUrls\": [");
		appendArray(Config.getInstance().getVidUrls(), json);// json.append(String.join(",",
																// Config.getInstance().getVidUrls()));
		json.append("],");
		json.append("\n\"fileExts\": [");
		appendArray(Config.getInstance().getFileExts(), json);
		json.append("],");
		json.append("\n\"vidExts\": [");
		appendArray(Config.getInstance().getVidExts(), json);
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
		json.append(sb.toString());
		json.append("],");
		String mimeTypes = "\n\"mimeList\": [\"video/\",\"audio/\",\"mpegurl\",\"f4m\",\"m3u8\"]";
		json.append(mimeTypes);
		json.append("\n}");
		return json.toString();
	}

	public static String getSync() {

		StringBuilder json = new StringBuilder();
		try {
			json.append("enabled:" + Config.getInstance().isBrowserMonitoringEnabled() + "\n");
			json.append("blockedHosts:" + String.join(",", Config.getInstance().getBlockedHosts()) + "\n");
			json.append("videoUrls:" + String.join(",", Config.getInstance().getVidUrls()) + "\n");
			json.append("fileExts:" + String.join(",", Config.getInstance().getFileExts()) + "\n");
			json.append("vidExts:" + String.join(",", Config.getInstance().getVidExts()) + "\n");
			json.append("mimeList:" + String.join(",", Config.getInstance().getVidMime()) + "\n");

			List<String> videoPopupItems = new ArrayList<>();
			for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
				String id = item.getMetadata().getId();
				String text = item.getFile();
				String info = item.getInfo();
				videoPopupItems.add(String.join("|", Base64.encode(id.getBytes("utf-8")),
						Base64.encode(text.getBytes("utf-8")), Base64.encode(info.getBytes("utf-8"))));
			}
			json.append("vidList:" + String.join(",", videoPopupItems) + "\n");
		} catch (Exception e) {
			e.printStackTrace();
		}

		//System.err.println(json);

//		json = new StringBuilder();
//		json.append("{\n\"enabled\": ");
//		json.append(Config.getInstance().isBrowserMonitoringEnabled());
//		json.append(",\n\"blockedHosts\": [");
//		appendArray(Config.getInstance().getBlockedHosts(), json);// json.append(String.join(",",
//																	// Config.getInstance().getBlockedHosts()));
//		json.append("],");
//		json.append("\n\"videoUrls\": [");
//		appendArray(Config.getInstance().getVidUrls(), json);// json.append(String.join(",",
//																// Config.getInstance().getVidUrls()));
//		json.append("],");
//		json.append("\n\"fileExts\": [");
//		appendArray(Config.getInstance().getFileExts(), json);
//		json.append("],");
//		json.append("\n\"vidExts\": [");
//		appendArray(Config.getInstance().getVidExts(), json);
//		json.append("],");
//		StringBuilder sb = new StringBuilder();
//		int count = 0;
//		for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
//			String id = item.getMetadata().getId();
//			String text = encode(item.getFile());
//			String info = item.getInfo();
//			if (count > 0)
//				sb.append(",");
//			sb.append(String.format("{\"id\": \"%s\", \"text\": \"%s\",\"info\":\"%s\"}", id, text, info));
//			count++;
//		}
//		json.append("\n\"vidList\": [");
//		json.append(sb.toString());
//		json.append("],");
//		String mimeTypes = "\n\"mimeList\": [\"video/\",\"audio/\",\"mpegurl\",\"f4m\",\"m3u8\"]";
//		json.append(mimeTypes);
//		json.append("\n}");
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
			Logger.log(e);
			XDMApp.instanceAlreadyRunning();
		}
		try {
			serverSock.close();
		} catch (Exception e) {
		}
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
					Logger.log("Lock acquired...");
					return;
				}

				// if lock is already acquired by some other process wait
				// and retry for at most 5 sec, after that throw error and
				// exit
				Thread.sleep(500);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
