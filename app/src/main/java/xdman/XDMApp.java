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

package xdman;

import java.awt.EventQueue;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.PasswordAuthentication;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.tinylog.Logger;

import xdman.downloaders.Downloader;
import xdman.downloaders.dash.DashDownloader;
import xdman.downloaders.ftp.FtpDownloader;
import xdman.downloaders.hds.HdsDownloader;
import xdman.downloaders.hls.HlsDownloader;
import xdman.downloaders.http.HttpDownloader;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.monitoring.BrowserMonitor;
import xdman.network.http.HttpContext;
import xdman.ui.components.BatchDownloadWnd;
import xdman.ui.components.ComponentInstaller;
import xdman.ui.components.DownloadCompleteWnd;
import xdman.ui.components.DownloadWindow;
import xdman.ui.components.MainWindow;
import xdman.ui.components.NewDownloadWindow;
import xdman.ui.components.TrayHandler;
import xdman.ui.components.VideoDownloadWindow;
import xdman.ui.components.VideoPopup;
import xdman.ui.components.VideoPopupItem;
import xdman.ui.laf.XDMLookAndFeel;
import xdman.ui.res.StringResource;
import xdman.util.*;

@SuppressWarnings({ "ResultOfMethodCallIgnored", "unused" })
public class XDMApp implements DownloadListener, DownloadWindowListener, Comparator<String> {
	public static final String GLOBAL_LOCK_FILE = ".xdm-global-lock";
	public static final String APP_VERSION = "7.2.11";
	public static final String XDM_WINDOW_TITLE = "XDM " + LocalDate.now().getYear();
	public static final String APP_UPDATE_URL = "https://api.github.com/repos/subhra74/xdm/releases/latest";
	public static final String APP_UPDATE_CHK_URL = "https://subhra74.github.io/xdm/update-checker.html?v=";
	public static final String APP_WIKI_URL = "https://github.com/subhra74/xdm/wiki";
	public static final String APP_HOME_URL = "https://github.com/subhra74/xdm";
	public static final String APP_TWITTER_URL = "https://twitter.com/XDM_subhra74";
	public static final String APP_FACEBOOK_URL = "https://www.facebook.com/XDM.subhra74/";
	public static final String[] ZOOM_LEVEL_STRINGS = { "Default", "50%", "75%", "100%", "125%", "150%", "200%", "250%",
			"300%", "350%", "400%", "450%", "500%" };
	public static final double[] ZOOM_LEVEL_VALUES = { -1, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5,
			5.0 };

	private final ArrayList<ListChangeListener> listChangeListeners;
	private final Map<String, DownloadEntry> downloads;
	private static XDMApp _this;
	private final HashMap<String, Downloader> downloaders;
	private final HashMap<String, DownloadWindow> downloadWindows;
	private long lastSaved;
	private final QueueManager queueManager;
	private LinkRefreshCallback refreshCallback;

	/**
	 * This buffer is used when there is a limit on maximum simultaneous downloads
	 * are started than permissible limit. If queues are also running then this
	 * buffer will be processed first.
	 */
	private final ArrayList<String> pendingDownloads;
	private static HashMap<String, String> paramMap;
	private MainWindow mainWindow;

	/**
	 * If main window in not created notification is stored in this variable
	 */
	private int pendingNotification = -1;

	private final ArrayList<VideoPopupItem> itemList = new ArrayList<>();

	public static void instanceStarted() {
		Logger.info("instance starting...");
		final XDMApp app = XDMApp.getInstance();
		EventQueue.invokeLater(() -> {
			if (!paramMap.containsKey("background")) {
				Logger.info("showing main window.");
				app.showMainWindow();
			}
			TrayHandler.createTray();
		});
		if (Config.getInstance().isFirstRun()) {
			if (XDMUtils.detectOS() != XDMUtils.WINDOWS) {
				XDMUtils.addToStartup();
			}
			NativeMessagingHostInstaller.installNativeMessagingHostForChrome();
			NativeMessagingHostInstaller.installNativeMessagingHostForFireFox();
			NativeMessagingHostInstaller.installNativeMessagingHostForChromium();
		}
		Logger.info("instance started.");
	}

	public static void instanceAlreadyRunning() {
		Logger.info("instance already running");
		ParamUtils.sendParam(paramMap);
		System.exit(0);
	}

	public static void start(String[] args) {

		Config.getInstance().load();
		if (Config.getInstance().getZoomLevelIndex() > 0) {
			double zoom = XDMApp.ZOOM_LEVEL_VALUES[Config.getInstance().getZoomLevelIndex()];
			Logger.info("Zoom index; " + Config.getInstance().getZoomLevelIndex() + " " + zoom);

			System.setProperty("sun.java2d.uiScale.enabled", "true");
			System.setProperty("sun.java2d.uiScale", String.format("%.2f", zoom));
		}

		paramMap = new HashMap<>();
		boolean expect = false;
		boolean winInstall = false;
		String key = "";
		for (String arg : args) {
			if (expect) {
				if (!key.isEmpty()) {
					paramMap.put(key, arg);
				}
				expect = false;
				continue;
			}
			if ("-u".equals(arg)) {
				key = "url";
				expect = true;
			} else if ("-m".equals(arg)) {
				paramMap.put("background", "true");
				expect = false;
			} else if ("-i".equals(arg)) {
				paramMap.put("installer", "true");
				expect = false;
				winInstall = true;
			} else if ("-s".equals(arg)) {
				key = "screen";
				expect = true;
			} else if ("-o".equals(arg) || "--output".equals(arg)) {
				key = "output";
				expect = true;
			} else if ("-q".equals(arg) || "--quiet".equals(arg)) {
				paramMap.put("quiet", "true");
				expect = false;
			}
		}

		if (winInstall) {
			try {
				SwingUtilities.invokeAndWait(() -> {
					Logger.info("wininstall");
					if (UpdateChecker.getComponentVersion() == null) {
						new ComponentInstaller().setVisible(true);
					}
				});
			} catch (Exception e) {
				Logger.error(e);
			}
		}

		Logger.info("starting monitoring...");
		BrowserMonitor.getInstance().startMonitoring();
	}

	public void showMainWindow() {
		if (this.mainWindow == null) {
			this.mainWindow = new MainWindow();
		}
		this.mainWindow.setVisible(true);
		this.mainWindow.toFront();
	}

	private XDMApp() {
		Logger.info("Init app");
		try {
			UIManager.setLookAndFeel(new XDMLookAndFeel());
		} catch (Exception e) {
			Logger.error(e);
		}

		Config.getInstance().setAutoShutdown(false);
		this.listChangeListeners = new ArrayList<>();
		this.downloads = new HashMap<>();
		this.downloaders = new HashMap<>();
		this.downloadWindows = new HashMap<>();
		this.loadDownloadList();
		this.lastSaved = System.currentTimeMillis();
		this.pendingDownloads = new ArrayList<>();
		this.queueManager = QueueManager.getInstance();
		this.queueManager.fixCorruptEntries(getDownloadIds(), this);
		QueueScheduler.getInstance().start();
		HttpContext.getInstance().init();
		if (Config.getInstance().isMonitorClipboard()) {
			ClipboardMonitor.getInstance().startMonitoring();
		}
	}

	public void exit() {
		this.saveDownloadList();
		this.queueManager.saveQueues();
		Config.getInstance().save();
		System.exit(0);
	}

	public void downloadFinished(String id) {
		DownloadEntry ent = this.downloads.get(id);
		ent.setState(XDMConstants.FINISHED);
		Downloader d = this.downloaders.remove(id);
		if (d != null && d.getSize() < 0) {
			ent.setSize(d.getDownloaded());
		}
		DownloadWindow wnd = this.downloadWindows.get(id);
		if (wnd != null) {
			wnd.close(XDMConstants.FINISHED, 0);
			this.downloadWindows.remove(id);
			if (ent.isStartedByUser()) {
				if (Config.getInstance().showDownloadCompleteWindow()) {
					new DownloadCompleteWnd(ent.getFile(), getFolder(ent)).setVisible(true);
				}
			}
		}
		this.notifyListeners(null);
		this.saveDownloadList();
		if (Config.getInstance().isExecAntivirus()) {
			if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getAntivirusExe())) {
				execAntivirus();
			}
		}

		this.processNextItem(id);
		if (this.isAllFinished()) {
			if (Config.getInstance().isAutoShutdown()) {
				this.initShutdown();
			}
			if (Config.getInstance().isExecCmd()) {
				this.execCmd();
			}
		}
	}

	public void downloadFailed(String id) {
		Downloader downloader = this.downloaders.remove(id);
		if (id == null) {
			Logger.warn("Download failed, id null");
			return;
		}
		DownloadWindow downloadWindow = this.downloadWindows.get(id);
		if (downloadWindow != null) {
			downloadWindow.close(XDMConstants.FAILED, downloader.getErrorCode());
			this.downloadWindows.remove(id);
		} else {
			Logger.warn("Wnd is null!!!");
		}
		DownloadEntry downloadEntry = this.downloads.get(id);
		downloadEntry.setState(XDMConstants.PAUSED);
		this.notifyListeners(id);
		this.saveDownloadList();
		Logger.info("removed");
		this.processNextItem(id);
	}

	public void downloadStopped(String id) {
		this.downloaders.remove(id);
		DownloadWindow downloadWindow = this.downloadWindows.get(id);
		if (downloadWindow != null) {
			downloadWindow.close(XDMConstants.PAUSED, 0);
			this.downloadWindows.remove(id);
		}
		DownloadEntry downloadEntry = this.downloads.get(id);
		downloadEntry.setState(XDMConstants.PAUSED);
		this.notifyListeners(id);
		this.saveDownloadList();
		this.processNextItem(id);
	}

	public void downloadConfirmed(String id) {
		Logger.info("confirmed " + id);
		Downloader d = this.downloaders.get(id);
		DownloadEntry downloadEntry = this.downloads.get(id);
		downloadEntry.setSize(d.getSize());
		if (d.isFileNameChanged()) {
			downloadEntry.setFile(d.getNewFile());
			downloadEntry.setCategory(XDMUtils.findCategory(d.getNewFile()));
			this.updateFileName(downloadEntry);
		}

		DownloadWindow downloadWindow = this.downloadWindows.get(id);
		if (downloadWindow != null) {
			downloadWindow.update(d, downloadEntry.getFile());
		}
		this.notifyListeners(id);
		this.saveDownloadList();
	}

	public void downloadUpdated(String id) {
		try {
			DownloadEntry downloadEntry = this.downloads.get(id);
			Downloader downloader = this.downloaders.get(id);
			if (downloader == null) {
				Logger.warn("################# sync error ##############");
				return;
			}
			downloadEntry.setSize(downloader.getSize());
			downloadEntry.setDownloaded(downloader.getDownloaded());
			downloadEntry.setProgress(downloader.getProgress());
			downloadEntry.setState(downloader.isAssembling() ? XDMConstants.ASSEMBLING : XDMConstants.DOWNLOADING);
			DownloadWindow downloadWindow = this.downloadWindows.get(id);
			if (downloadWindow != null) {
				downloadWindow.update(downloader, downloadEntry.getFile());
			}
		} finally {
			this.notifyListeners(id);
			long now = System.currentTimeMillis();
			if (now - this.lastSaved > 5000) {
				this.saveDownloadList();
				this.lastSaved = now;
			}
		}
	}

	public synchronized static XDMApp getInstance() {
		if (_this == null) {
			_this = new XDMApp();
		}
		return _this;
	}

	public void addLinks(final List<HttpMetadata> list) {
		SwingUtilities.invokeLater(() -> {
			BatchDownloadWnd wnd = new BatchDownloadWnd(list);
			wnd.setVisible(true);
		});
	}

	public void addDownload(final HttpMetadata metadata, final String file) {
		if (this.refreshCallback != null) {
			if (this.refreshCallback.isValidLink(metadata)) {
				return;
			}
		}
		SwingUtilities.invokeLater(() -> {
			String fileName;
			String folderPath;

			if (StringUtils.isNullOrEmptyOrBlank(file)) {
				if (metadata != null) {
					fileName = XDMUtils.getFileName(metadata.getUrl());
				} else {
					fileName = null;
				}
				folderPath = null;
			} else {
				var path = Paths.get(file);

				fileName = path.getFileName().toString();

				var parentPath = path.getParent();
				if (parentPath != null && parentPath.isAbsolute()) {
					folderPath = parentPath.toString();
				} else {
					String downloadFolderPath;
					if (Config.getInstance().isForceSingleFolder()) {
						downloadFolderPath = Config.getInstance().getDownloadFolder();
					} else {
						var category = XDMUtils.findCategory(file);
						downloadFolderPath = XDMApp.getInstance().getFolder(category);
					}

					if (parentPath != null) {
						folderPath = Paths.get(downloadFolderPath, parentPath.toString()).toString();
					} else {
						folderPath = downloadFolderPath;
					}
				}
			}

			if (metadata != null
					&& (Config.getInstance().isQuietMode() || Config.getInstance().isDownloadAutoStart())) {
				createDownload(fileName, folderPath, metadata, true, "", 0, 0);
				return;
			}

			new NewDownloadWindow(metadata, fileName, folderPath).setVisible(true);
		});
	}

	public void addVideo(final HttpMetadata metadata, final String file) {
		if (this.refreshCallback != null) {
			if (this.refreshCallback.isValidLink(metadata)) {
				return;
			}
		}
		SwingUtilities.invokeLater(() -> {
			if (!XDMUtils.isFFmpegInstalled()) {
				if (JOptionPane.showConfirmDialog(null, StringResource.get("MSG_INSTALL_ADDITIONAL_COMPONENTS"),
						StringResource.get("MSG_COMPONENT_TITLE"),
						JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
					FFmpegDownloader fd = new FFmpegDownloader();
					fd.start();
				}
				return;
			}
			new VideoDownloadWindow(metadata, file).setVisible(true);
		});
	}

	public void addMedia(final HttpMetadata metadata, final String file, final String info) {
		Logger.info("video notification: " + Config.getInstance().isShowVideoNotification());
		if (Config.getInstance().isShowVideoNotification()) {
			SwingUtilities.invokeLater(() -> {
				VideoPopup.getInstance().addVideo(metadata, file, info);
				BrowserMonitor.getInstance().updateSettingsAndStatus();
			});
		}
	}

	public void youtubeVideoTitleUpdated(String url, String title) {
		if (Config.getInstance().isShowVideoNotification()) {
			SwingUtilities.invokeLater(() -> {
				if (VideoPopup.hasInstance()) {
					VideoPopup.getInstance().updateYoutubeTitle(url, title);
					BrowserMonitor.getInstance().updateSettingsAndStatus();
				}
			});
		}
	}

	public void createDownload(String file, String folder, HttpMetadata metadata, boolean now, String queueId,
			int formatIndex, int streamIndex) {
		metadata.save();
		DownloadEntry ent = new DownloadEntry();
		ent.setId(metadata.getId());
		ent.setOutputFormatIndex(formatIndex);
		ent.setState(XDMConstants.PAUSED);
		ent.setFile(file);
		ent.setFolder(folder);
		ent.setTempFolder(Config.getInstance().getTemporaryFolder());
		ent.setCategory(XDMUtils.findCategory(file));
		ent.setDate(System.currentTimeMillis());
		putInQueue(queueId, ent);
		ent.setStartedByUser(now);
		this.downloads.put(metadata.getId(), ent);
		saveDownloadList();
		if (!now) {
			DownloadQueue downloadQueue = this.queueManager.getQueueById(queueId);
			if (downloadQueue != null && downloadQueue.isRunning()) {
				Logger.info("Queue is running, if no pending download pickup next available download");
				downloadQueue.next();
			}
		}
		if (now) {
			startDownload(metadata.getId(), metadata, ent, streamIndex);
		}
		notifyListeners(null);
	}

	private void startDownload(String id, HttpMetadata metadata, DownloadEntry downloadEntry, int streams) {
		if (!this.checkAndBufferRequests(id)) {
			Logger.info("starting " + id + " with: " + metadata + " is dash: " + (metadata instanceof DashMetadata));
			Downloader downloader = null;
			if (metadata instanceof DashMetadata) {
				Logger.info("Dash download with stream: " + streams);
				if (streams == 1) {
					DashMetadata dashMetadata = (DashMetadata) metadata;
					dashMetadata.setUrl(dashMetadata.getUrl2());// set video url as main url
					dashMetadata.setUrl2(null);
				} else if (streams == 2) {
					DashMetadata dashMetadata = (DashMetadata) metadata;
					dashMetadata.setUrl2(null);
				} else {
					Logger.info("Dash download created");
					DashMetadata dashMetadata = (DashMetadata) metadata;
					downloader = new DashDownloader(id, downloadEntry.getTempFolder(), dashMetadata);
				}
			}
			if (metadata instanceof HlsMetadata) {
				Logger.info("Hls download created");
				downloader = new HlsDownloader(id, downloadEntry.getTempFolder(), (HlsMetadata) metadata);
			}
			if (metadata instanceof HdsMetadata) {
				Logger.info("Hds download created");
				downloader = new HdsDownloader(id, downloadEntry.getTempFolder(), (HdsMetadata) metadata);
			}
			if (downloader == null) {
				if (metadata.getType() == XDMConstants.FTP) {
					downloader = new FtpDownloader(id, downloadEntry.getTempFolder(), metadata);
				} else {
					downloader = new HttpDownloader(id, downloadEntry.getTempFolder(), metadata);
				}
			}

			downloader.setOutputMediaFormat(downloadEntry.getOutputFormatIndex());
			this.downloaders.put(id, downloader);
			downloader.registerListener(this);
			downloadEntry.setState(XDMConstants.DOWNLOADING);
			downloader.start();

			if (!Config.getInstance().isQuietMode() && Config.getInstance().showDownloadWindow()) {
				DownloadWindow downloadWindow = new DownloadWindow(id, this);
				this.downloadWindows.put(id, downloadWindow);
				downloadWindow.setVisible(true);
			}
		} else {
			Logger.warn(id + ": Maximum download limit reached, queueing request");
		}
	}

	public void pauseDownload(String id) {
		Downloader downloader = this.downloaders.get(id);
		if (downloader != null) {
			downloader.stop();
			downloader.unregisterListener();
		}
	}

	public void resumeDownload(String id, boolean startedByUser) {
		DownloadEntry downloadEntry = this.downloads.get(id);
		downloadEntry.setStartedByUser(startedByUser);
		if (downloadEntry.getState() == XDMConstants.PAUSED || downloadEntry.getState() == XDMConstants.FAILED) {
			if (!this.checkAndBufferRequests(id)) {
				downloadEntry.setState(XDMConstants.DOWNLOADING);
				HttpMetadata metadata = HttpMetadata.load(id);
				if (!Config.getInstance().isQuietMode() && Config.getInstance().showDownloadWindow()
						&& downloadEntry.isStartedByUser()) {
					DownloadWindow downloadWindow = new DownloadWindow(id, this);
					this.downloadWindows.put(id, downloadWindow);
					downloadWindow.setVisible(true);
				}
				Downloader downloader = null;
				if (metadata instanceof DashMetadata) {
					DashMetadata dm = (DashMetadata) metadata;
					Logger.info("Dash download- url1: " + dm.getUrl() + " url2: " + dm.getUrl2());
					downloader = new DashDownloader(id, downloadEntry.getTempFolder(), dm);
				}
				if (metadata instanceof HlsMetadata) {
					HlsMetadata hm = (HlsMetadata) metadata;
					Logger.info("HLS download- url1: " + hm.getUrl());
					downloader = new HlsDownloader(id, downloadEntry.getTempFolder(), hm);
				}
				if (metadata instanceof HdsMetadata) {
					HdsMetadata hdsMetadata = (HdsMetadata) metadata;
					Logger.info("HLS download- url1: " + hdsMetadata.getUrl());
					downloader = new HdsDownloader(id, downloadEntry.getTempFolder(), hdsMetadata);
				}
				if (downloader == null) {
					Logger.info("normal download");
					if (metadata.getType() == XDMConstants.FTP) {
						downloader = new FtpDownloader(id, downloadEntry.getTempFolder(), metadata);
					} else {
						downloader = new HttpDownloader(id, downloadEntry.getTempFolder(), metadata);
					}
				}
				this.downloaders.put(id, downloader);
				downloader.setOutputMediaFormat(downloadEntry.getOutputFormatIndex());
				downloader.registerListener(this);
				downloader.resume();

			} else {
				Logger.warn(id + ": Maximum download limit reached, queueing request");
			}
			notifyListeners(null);
		}
	}

	public void restartDownload(String id) {
		DownloadEntry downloadEntry = this.downloads.get(id);
		if (downloadEntry.getState() == XDMConstants.PAUSED || downloadEntry.getState() == XDMConstants.FAILED
				|| downloadEntry.getState() == XDMConstants.FINISHED) {
			downloadEntry.setState(XDMConstants.PAUSED);
			clearData(downloadEntry);
			resumeDownload(id, true);
		}
	}

	synchronized public void addListener(ListChangeListener listener) {
		this.listChangeListeners.add(listener);
	}

	synchronized public void removeListener(ListChangeListener listener) {
		this.listChangeListeners.remove(listener);
	}

	private void notifyListeners(String id) {
		if (this.listChangeListeners != null) {
			for (ListChangeListener listChangeListener : this.listChangeListeners)
				if (id != null)
					listChangeListener.listItemUpdated(id);
				else
					listChangeListener.listChanged();
		}
	}

	public DownloadEntry getEntry(String id) {
		return this.downloads.get(id);
	}

	public ArrayList<String> getDownloadList(int category, int state, String searchText, String queueId) {
		ArrayList<String> idList = new ArrayList<>();
		for (String key : this.downloads.keySet()) {
			DownloadEntry ent = this.downloads.get(key);
			if (state == XDMConstants.ALL || state == (ent.getState() == XDMConstants.FINISHED ? XDMConstants.FINISHED
					: XDMConstants.UNFINISHED)) {
				if (category == XDMConstants.ALL || category == ent.getCategory()) {
					boolean matched = false;
					if (!"ALL".equals(queueId)) {
						if (queueId != null) {
							if (!queueId.equals(ent.getQueueId())) {
								continue;
							}
						}
					}

					if (searchText != null && searchText.length() > 0) {
						if (ent.getFile().contains(searchText)) {
							matched = true;
						}
					} else {
						matched = true;
					}
					if (matched) {
						idList.add(ent.getId());
					}
				}
			}
		}
		return idList;
	}

	private void clearData(DownloadEntry ent) {
		if (ent == null)
			return;
		File folder = new File(ent.getTempFolder(), ent.getId());
		File[] files = folder.listFiles();
		if (files != null) {
			for (File file : files) {
				file.delete();
			}
		}
		folder.delete();
	}

	@Override
	public String getOutputFolder(String id) {
		DownloadEntry downloadEntry = this.downloads.get(id);
		if (downloadEntry == null) {
			return Config.getInstance().getCategoryOther();
		} else {
			return this.getFolder(downloadEntry);
		}
	}

	@Override
	public String getOutputFile(String id, boolean update) {
		DownloadEntry downloadEntry = this.downloads.get(id);
		if (update) {
			updateFileName(downloadEntry);
		}
		return downloadEntry.getFile();
	}

	public String getFolder(DownloadEntry downloadEntry) {
		if (downloadEntry.getFolder() != null) {
			return downloadEntry.getFolder();
		}
		if (Config.getInstance().isForceSingleFolder()) {
			return Config.getInstance().getDownloadFolder();
		}
		int category = downloadEntry.getCategory();
		return this.getFolder(category);
	}

	public String getFolder(int category) {
		switch (category) {
		case XDMConstants.DOCUMENTS:
			return Config.getInstance().getCategoryDocuments();
		case XDMConstants.MUSIC:
			return Config.getInstance().getCategoryMusic();
		case XDMConstants.VIDEO:
			return Config.getInstance().getCategoryVideos();
		case XDMConstants.PROGRAMS:
			return Config.getInstance().getCategoryPrograms();
		case XDMConstants.COMPRESSED:
			return Config.getInstance().getCategoryCompressed();
		default:
			return Config.getInstance().getCategoryOther();
		}
	}

	private void loadDownloadList() {
		File file = new File(Config.getInstance().getDataFolder(), "downloads.txt");
		this.loadDownloadList(file);
	}

	public void loadDownloadList(File file) {
		if (!file.exists()) {
			return;
		}
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line = reader.readLine();
			if (line == null) {
				throw new NullPointerException("Unexpected EOF");
			}
			int count = Integer.parseInt(line.trim());
			for (int i = 0; i < count; i++) {
				int fieldCount = Integer.parseInt(XDMUtils.readLineSafe(reader).trim());
				DownloadEntry downloadEntry = new DownloadEntry();
				for (int j = 0; j < fieldCount; j++) {
					String ln = reader.readLine();
					if (ln == null) {
						return;
					}
					int index = ln.indexOf(":");
					if (index > 0) {
						String key = ln.substring(0, index).trim();
						String val = ln.substring(index + 1).trim();
						if (key.equals("id")) {
							downloadEntry.setId(val);
						}
						if (key.equals("file")) {
							downloadEntry.setFile(val);
						}
						if (key.equals("category")) {
							downloadEntry.setCategory(Integer.parseInt(val));
						}
						if (key.equals("state")) {
							int state = Integer.parseInt(val);
							downloadEntry.setState(state == XDMConstants.FINISHED ? state : XDMConstants.PAUSED);
						}
						if (key.equals("folder")) {
							downloadEntry.setFolder(val);
						}
						if (key.equals("date")) {
							downloadEntry.setDate(dateFormat.parse(val).getTime());
						}
						if (key.equals("downloaded")) {
							downloadEntry.setDownloaded(Long.parseLong(val));
						}
						if (key.equals("size")) {
							downloadEntry.setSize(Long.parseLong(val));
						}
						if (key.equals("progress")) {
							downloadEntry.setProgress(Integer.parseInt(val));
						}
						if (key.equals("queueid")) {
							downloadEntry.setQueueId(val);
						}
						if (key.equals("formatIndex")) {
							downloadEntry.setOutputFormatIndex(Integer.parseInt(val));
						}
						if (key.equals("tempfolder")) {
							downloadEntry.setTempFolder(val);
						}
					}
				}
				this.downloads.put(downloadEntry.getId(), downloadEntry);
			}
		} catch (Exception e) {
			Logger.error(e);
		}

	}

	private void saveDownloadList() {
		File file = new File(Config.getInstance().getDataFolder(), "downloads.txt");
		this.saveDownloadList(file);
	}

	public void saveDownloadList(File file) {
		int count = this.downloads.size();
		BufferedWriter writer = null;
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		String newLine = System.getProperty("line.separator");
		try {
			writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
			writer.write(count + "");
			writer.newLine();
			for (String key : this.downloads.keySet()) {
				DownloadEntry downloadEntry = this.downloads.get(key);
				int counter = 0;
				StringBuilder builder = new StringBuilder();
				builder.append("id: ").append(downloadEntry.getId()).append(newLine);
				counter++;
				builder.append("file: ").append(downloadEntry.getFile()).append(newLine);
				counter++;
				builder.append("category: ").append(downloadEntry.getCategory()).append(newLine);
				counter++;
				builder.append("state: ").append(downloadEntry.getState()).append(newLine);
				counter++;
				if (downloadEntry.getFolder() != null) {
					builder.append("folder: ").append(downloadEntry.getFolder()).append(newLine);
					counter++;
				}
				builder.append("date: ").append(dateFormat.format(new Date(downloadEntry.getDate()))).append(newLine);
				counter++;
				builder.append("downloaded: ").append(downloadEntry.getDownloaded()).append(newLine);
				counter++;
				builder.append("size: ").append(downloadEntry.getSize()).append(newLine);
				counter++;
				builder.append("progress: ").append(downloadEntry.getProgress()).append(newLine);
				counter++;
				if (downloadEntry.getTempFolder() != null) {
					builder.append("tempfolder: ").append(downloadEntry.getTempFolder()).append(newLine);
					counter++;
				}
				if (downloadEntry.getQueueId() != null) {
					builder.append("queueid: ").append(downloadEntry.getQueueId()).append(newLine);
					counter++;
				}
				builder.append("formatIndex: ").append(downloadEntry.getOutputFormatIndex()).append(newLine);
				counter++;
				writer.write(counter + newLine);
				writer.write(builder.toString());

			}
			IOUtils.closeFlow(writer);
		} catch (Exception e) {
			Logger.error(e);
			IOUtils.closeFlow(writer);
		}
	}

	public void hidePrgWnd(String id) {
		DownloadWindow downloadWindow = this.downloadWindows.get(id);
		if (downloadWindow != null) {
			this.downloadWindows.remove(id);
			downloadWindow.close(XDMConstants.PAUSED, 0);
		}
	}

	private synchronized int getActiveDownloadCount() {
		int count = 0;
		for (String key : this.downloads.keySet()) {
			DownloadEntry downloadEntry = this.downloads.get(key);
			int state = downloadEntry.getState();
			if (state == XDMConstants.FINISHED || state == XDMConstants.PAUSED || state == XDMConstants.FAILED)
				continue;
			count++;
		}
		return count;
	}

	private synchronized boolean checkAndBufferRequests(String id) {
		int actCount = getActiveDownloadCount();
		if (Config.getInstance().getMaxDownloads() > 0 && actCount >= Config.getInstance().getMaxDownloads()) {
			Logger.info("active: " + actCount + " max: " + Config.getInstance().getMaxDownloads());
			if (!this.pendingDownloads.contains(id)) {
				this.pendingDownloads.add(id);
			}
			return true;
		}
		return false;
	}

	private synchronized void processNextItem(String lastId) {
		this.processPendingRequests();
		if (lastId == null)
			return;
		DownloadEntry downloadEntry = getEntry(lastId);
		if (downloadEntry == null) {
			return;
		}
		DownloadQueue queue;
		if ("".equals(downloadEntry.getQueueId())) {
			queue = this.queueManager.getDefaultQueue();
		} else {
			queue = this.queueManager.getQueueById(downloadEntry.getQueueId());
		}
		if (queue != null && queue.isRunning()) {
			queue.next();
		}
	}

	private void processPendingRequests() {
		int activeCount = getActiveDownloadCount();
		int maxDownloadCount = Config.getInstance().getMaxDownloads();
		List<String> tobeStartedIds = new ArrayList<>();
		if (maxDownloadCount - activeCount > 0) {
			for (int i = 0; i < Math.min(maxDownloadCount, this.pendingDownloads.size()); i++) {
				String ent = this.pendingDownloads.get(i);
				tobeStartedIds.add(ent);
			}
		}
		if (tobeStartedIds.size() > 0) {
			for (String id : tobeStartedIds) {
				this.pendingDownloads.remove(id);
				DownloadEntry ent = getEntry(id);
				if (ent != null) {
					resumeDownload(id, ent.isStartedByUser());
				}
			}
		}
	}

	public boolean queueItemPending(String queueId) {
		if (queueId == null)
			return false;
		for (String id : this.pendingDownloads) {
			DownloadEntry ent = getEntry(id);
			if (ent == null || ent.getQueueId() == null)
				continue;
			if (ent.getQueueId().equals(queueId)) {
				return true;
			}
		}
		return false;
	}

	public ArrayList<DownloadQueue> getQueueList() {
		return this.queueManager.getQueueList();
	}

	public DownloadQueue getQueueById(String queueId) {
		return this.queueManager.getQueueById(queueId);
	}

	private void putInQueue(String queueId, DownloadEntry entry) {
		DownloadQueue queue = getQueueById(queueId);
		String id = entry.getId();
		if (queue == null) {
			Logger.warn("No queue found for: '" + queueId + "'");
			return;
		}
		String qid = entry.getQueueId();
		DownloadQueue oldQ = getQueueById(qid);
		Logger.info("Adding to: '" + queueId + "'");
		if (!queue.getQueueId().equals(qid)) {
			if (oldQ != null) {
				oldQ.removeFromQueue(id);
			}
			entry.setQueueId(queueId);
			queue.addToQueue(id);
		}
	}

	@Override
	public int compare(String key1, String key2) {
		DownloadEntry ent1 = getEntry(key1);
		DownloadEntry ent2 = getEntry(key2);
		if (ent1 == null)
			return -1;
		if (ent2 == null)
			return 1;
		return Long.compare(ent1.getDate(), ent2.getDate());
	}

	private Iterator<String> getDownloadIds() {
		return this.downloads.keySet().iterator();
	}

	public boolean isAllFinished() {
		if (getActiveDownloadCount() != 0) {
			return false;
		}
		if (this.pendingDownloads.size() != 0) {
			return false;
		}
		for (int i = 0; i < QueueManager.getInstance().getQueueList().size(); i++) {
			DownloadQueue q = QueueManager.getInstance().getQueueList().get(i);
			if (q.hasPendingItems()) {
				return false;
			}
		}
		return true;
	}

	private void initShutdown() {
		Logger.info("Initiating shutdown");
		int os = XDMUtils.detectOS();
		if (os == XDMUtils.LINUX) {
			LinuxUtils.initShutdown();
		} else if (os == XDMUtils.WINDOWS) {
			WinUtils.initShutdown();
		} else if (os == XDMUtils.MAC) {
			MacUtils.initShutdown();
		}
	}

	public int deleteDownloads(ArrayList<String> idList, boolean outfile) {
		int c = 0;
		for (String id : idList) {
			DownloadEntry ent = getEntry(id);
			if (ent != null) {
				if (ent.getState() == XDMConstants.FINISHED || ent.getState() == XDMConstants.PAUSED
						|| ent.getState() == XDMConstants.FAILED) {
					this.downloads.remove(id);
					if (this.pendingDownloads.contains(id)) {
						this.pendingDownloads.remove(id);
					}
					String qId = ent.getQueueId();
					if (qId != null) {
						DownloadQueue q = getQueueById(qId);
						if (q != null) {
							if (q.getQueueId().length() > 0) {
								q.removeFromQueue(id);
							}
						}
					}
					deleteFiles(ent, outfile);
					c++;
				}
			}
		}
		saveDownloadList();
		notifyListeners(null);
		return idList.size() - c;
	}

	private void deleteFiles(DownloadEntry ent, boolean outfile) {
		if (ent == null)
			return;
		String id = ent.getId();
		Logger.info("Deleting metadata for " + id);
		File mf = new File(Config.getInstance().getMetadataFolder(), id);
		boolean deleted = mf.delete();
		Logger.info("Deleted manifest " + id + " " + deleted);
		File df = new File(ent.getTempFolder(), id);
		File[] files = df.listFiles();
		if (files != null && files.length > 0) {
			for (File f : files) {
				deleted = f.delete();
				Logger.info("Deleted tmp file " + id + " " + deleted);
			}
		}
		deleted = df.delete();
		Logger.info("Deleted tmp folder " + id + " " + deleted);
		if (outfile) {
			File f = new File(XDMApp.getInstance().getFolder(ent), ent.getFile());
			f.delete();
		}
	}

	public void changeOutputFile(String id) {

	}

	public String getURL(String id) {
		try {
			HttpMetadata metadata = HttpMetadata.load(id);
			if (metadata instanceof DashMetadata) {
				DashMetadata dm = (DashMetadata) metadata;
				return dm.getUrl() + "\n" + dm.getUrl2();
			} else {
				return metadata.getUrl();
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return "";
	}

	public void registerRefreshCallback(LinkRefreshCallback callback) {
		this.refreshCallback = callback;
	}

	public void unregisterRefreshCallback() {
		this.refreshCallback = null;
	}

	public void deleteCompleted() {
		Iterator<String> allIds = downloads.keySet().iterator();
		ArrayList<String> idList = new ArrayList<>();
		while (allIds.hasNext()) {
			String id = allIds.next();
			DownloadEntry ent = downloads.get(id);
			if (ent.getState() == XDMConstants.FINISHED) {
				idList.add(id);
			}
		}
		deleteDownloads(idList, false);
	}

	public boolean promptCredential(String id, String msg, boolean proxy) {
		DownloadEntry ent = getEntry(id);
		if (ent == null)
			return false;
		if (!ent.isStartedByUser())
			return false;
		PasswordAuthentication passwordAuth = getCredential(msg, proxy);
		if (passwordAuth == null) {
			return false;
		}
		if (proxy) {
			Config.getInstance().setProxyUser(passwordAuth.getUserName());
			if (passwordAuth.getPassword() != null) {
				Config.getInstance().setProxyPass(new String(passwordAuth.getPassword()));
			}
		} else {
			Logger.info("saving password for: " + msg);
			CredentialManager.getInstance().addCredentialForHost(msg, passwordAuth);
		}
		return true;
	}

	private PasswordAuthentication getCredential(String msg, boolean proxy) {
		JTextField user = new JTextField(30);
		JPasswordField pass = new JPasswordField(30);

		String prompt = proxy ? StringResource.get("PROMPT_PROXY")
				: String.format(StringResource.get("PROMPT_SERVER"), msg);

		Object[] obj = new Object[5];
		obj[0] = prompt;
		obj[1] = StringResource.get("DESC_USER");
		obj[2] = user;
		obj[3] = StringResource.get("DESC_PASS");
		obj[4] = pass;

		if (JOptionPane.showOptionDialog(null, obj, StringResource.get("PROMPT_CRED"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, null, null) == JOptionPane.OK_OPTION) {
			return new PasswordAuthentication(user.getText(), pass.getPassword());
		}
		return null;
	}

	private void execCmd() {
		if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getCustomCmd())) {
			XDMUtils.exec(Config.getInstance().getCustomCmd());
		}
	}

	private void execAntivirus() {
		XDMUtils.exec(Config.getInstance().getAntivirusExe() + " "
				+ (Config.getInstance().getAntivirusCmd() == null ? "" : Config.getInstance().getAntivirusCmd()));
	}

	private void updateFileName(DownloadEntry ent) {
		if (Config.getInstance().getDuplicateAction() == XDMConstants.DUP_ACT_OVERWRITE) {
			return;
		}
		Logger.info("checking for same named file on disk...");
		String id = ent.getId();
		String outputFolder = getOutputFolder(id);
		File f = new File(outputFolder, ent.getFile());
		int c = 1;
		String ext = XDMUtils.getExtension(f.getAbsolutePath());
		if (ext == null) {
			ext = "";
		}
		String f2 = XDMUtils.getFileNameWithoutExtension(ent.getFile());
		while (f.exists()) {
			f = new File(outputFolder, f2 + "_" + c + ext);
			c++;
		}
		Logger.info("Updating file name- old: " + ent.getFile() + " new: " + f.getName());
		ent.setFile(f.getName());
	}

	public void importList(File file) {
		loadDownloadList(file);
	}

	public void exportList(File file) {
		saveDownloadList(file);
	}

	public void notifyComponentUpdate() {
		pendingNotification = UpdateChecker.COMP_UPDATE_AVAILABLE;
		if (mainWindow != null) {
			mainWindow.showNotification();
		}
	}

	public void notifyComponentInstall() {
		pendingNotification = UpdateChecker.COMP_NOT_INSTALLED;
		if (mainWindow != null) {
			mainWindow.showNotification();
		}
	}

	public void notifyAppUpdate() {
		pendingNotification = UpdateChecker.APP_UPDATE_AVAILABLE;
		if (mainWindow != null) {
			mainWindow.showNotification();
		}
	}

	public void clearNotifications() {
		pendingNotification = -1;
		if (mainWindow != null) {
			mainWindow.showNotification();
		}
	}

	public int getNotification() {
		return pendingNotification;
	}

	private void openTempFolder(String id) {
		DownloadEntry ent = getEntry(id);
		if (ent == null)
			return;
		File df = new File(ent.getTempFolder(), id);
		try {
			XDMUtils.openFolder(null, df.getAbsolutePath());
		} catch (Exception e) {
			Logger.error(e);
		}
	}

	public void openPreview(String id) {
		DownloadEntry ent = XDMApp.getInstance().getEntry(id);
		if (ent != null && (ent.getCategory() == XDMConstants.VIDEO || ent.getCategory() == XDMConstants.MUSIC)) {
			if (XDMUtils.isFFmpegInstalled()) {
				XDMApp.getInstance().openPreviewPlayer(id);
			} else {
				JOptionPane.showMessageDialog(null, StringResource.get("LBL_COMPONENT_MISSING"));
			}
		} else if (JOptionPane.showConfirmDialog(null, StringResource.get("LBL_NOT_A_VIDEO"), "Preview",
				JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
			XDMApp.getInstance().openTempFolder(id);
		}
	}

	private void openPreviewPlayer(String id) {
		XDMUtils.browseURL("http://127.0.0.1:9614/preview/media/" + id);
	}

	public void showPrgWnd(String id) {
		DownloadEntry ent = getEntry(id);
		if (ent == null) {
			return;
		}
		if (ent.getState() == XDMConstants.FINISHED || ent.getState() == XDMConstants.PAUSED
				|| ent.getState() == XDMConstants.FAILED) {
			return;
		}
		DownloadWindow wnd = downloadWindows.get(id);
		if (wnd == null) {
			wnd = new DownloadWindow(id, this);
			downloadWindows.put(id, wnd);
			wnd.setVisible(true);
		}
	}

	public void fileNameChanged(String id) {
		notifyListeners(id);
	}

	public ArrayList<VideoPopupItem> getVideoItemsList() {
		return itemList;
	}

	public MainWindow getMainWindow() {
		return mainWindow;
	}
}