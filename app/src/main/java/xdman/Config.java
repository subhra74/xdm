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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.tinylog.Logger;

import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings({ "unused", "ResultOfMethodCallIgnored" })
public class Config {
	private static Config _config;
	private final String metadataFolder;
	private final String dataFolder;
	private final List<MonitoringListener> listeners;
	private boolean forceSingleFolder;
	private boolean monitoring;
	private String temporaryFolder;
	private String downloadFolder;
	private int sortField;
	private boolean sortAsc;
	private int categoryFilter;
	private int stateFilter;
	private String searchText;
	private int maxSegments;
	private int minSegmentSize;
	private int speedLimit;
	private boolean showDownloadWindow;
	private boolean showDownloadCompleteWindow;
	private int parallelDownloads;
	private boolean autoShutdown;
	private int duplicateAction;
	private boolean quietMode;
	private String[] blockedHosts, vidUrls, fileExits, vidExits, vidMime;
	private String[] defaultFileTypes, defaultVideoTypes;
	private int networkTimeout, tcpWindowSize;
	private int proxyMode;
	private String proxyPac, proxyHost, socksHost;
	private int proxyPort, socksPort;
	private String proxyUser, proxyPass;
	private boolean showVideoNotification;
	private int minVidSize;
	private boolean keepAwake, execCmd, execAntivirus, autoStart;
	private String customCmd, antivirusCmd, antivirusExe;
	private boolean firstRun;
	private String language;
	private boolean monitorClipboard;
	private String categoryOther, categoryDocuments, categoryMusic, categoryVideos, categoryPrograms,
			categoryCompressed;
	private boolean downloadAutoStart;
	private boolean fetchTs;
	private boolean noTransparency;
	private boolean hideTray;
	private String lastFolder;
	private String queueIdFilter;
	private boolean showVideoListOnlyInBrowser;
	private int zoomLevelIndex = 0;

	private Config() {
		this.forceSingleFolder = false;
		File f = new File(System.getProperty("user.home"), ".xdman");
		if (!f.exists()) {
			f.mkdirs();
		}
		this.dataFolder = f.getAbsolutePath();
		f = new File(this.dataFolder, "metadata");
		if (!f.exists()) {
			f.mkdir();
		}
		this.metadataFolder = f.getAbsolutePath();
		f = new File(this.dataFolder, "temp");
		if (!f.exists()) {
			f.mkdir();
		}

		this.temporaryFolder = f.getAbsolutePath();
		this.downloadFolder = XDMUtils.getDownloadsFolder();
		if (!new File(this.downloadFolder).exists()) {
			File file = new File(System.getProperty("user.home"), "Downloads");
			file.mkdirs();
			this.downloadFolder = file.getAbsolutePath();
		}

		this.monitoring = true;
		this.showDownloadWindow = true;
		this.setMaxSegments(8);
		this.setMinSegmentSize(256 * 1024);
		this.parallelDownloads = 100;
		this.minVidSize = 1024 * 1024;
		this.defaultFileTypes = new String[] { "3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "GZ", "ISO",
				"MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ" };
		this.fileExits = this.defaultFileTypes;
		this.autoShutdown = false;
		this.blockedHosts = new String[] { "update.microsoft.com", "windowsupdate.com", "thwawte.com" };
		this.defaultVideoTypes = new String[] { "MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
				"MOV", "MPG", "MPEG", "OPUS" };
		this.vidExits = this.defaultVideoTypes;
		this.vidUrls = new String[] { ".facebook.com|pagelet", "player.vimeo.com/", "instagram.com/p/" };
		this.vidMime = new String[] { "video/", "audio/", "mpegurl", "f4m", "m3u8" };
		this.networkTimeout = 60;
		this.tcpWindowSize = 0;
		this.speedLimit = 0;
		this.proxyMode = 0;
		this.proxyPort = 0;
		this.socksPort = 0;
		this.proxyPac = this.proxyHost = this.proxyUser = this.proxyPass = this.socksHost = "";
		this.showVideoNotification = true;
		this.showDownloadCompleteWindow = true;
		this.firstRun = true;
		this.language = "en";
		this.monitorClipboard = false;
		this.noTransparency = false;
		this.hideTray = true;
		this.listeners = new ArrayList<>();
	}

	public static synchronized Config getInstance() {
		if (_config == null) {
			_config = new Config();
		}
		return _config;
	}

	public void addConfigListener(MonitoringListener listener) {
		this.listeners.add(listener);
	}

	public String getLanguage() {
		return this.language;
	}

	public void setLanguage(String language) {
		this.language = language;
	}

	public void save() {
		FileWriter fw = null;
		try {
			File file = new File(System.getProperty("user.home"), ".xdman/config.txt");
			fw = new FileWriter(file);
			String newLine = "\n";
			fw.write("monitoring:" + this.monitoring + newLine);
			fw.write("downloadFolder:" + this.downloadFolder + newLine);
			fw.write("temporaryFolder:" + this.temporaryFolder + newLine);
			fw.write("parallalDownloads:" + this.parallelDownloads + newLine);
			fw.write("maxSegments:" + this.maxSegments + newLine);
			fw.write("networkTimeout:" + this.networkTimeout + newLine);
			fw.write("tcpWindowSize2:" + this.tcpWindowSize + newLine);
			fw.write("minSegmentSize2:" + this.minSegmentSize + newLine);
			fw.write("minVidSize:" + this.minVidSize + newLine);
			fw.write("duplicateAction:" + this.duplicateAction + newLine);
			fw.write("speedLimit:" + this.speedLimit + newLine);
			fw.write("showDownloadWindow:" + this.showDownloadWindow + newLine);
			fw.write("showDownloadCompleteWindow:" + this.showDownloadCompleteWindow + newLine);
			fw.write("blockedHosts:" + XDMUtils.appendArray2Str(this.blockedHosts) + newLine);
			fw.write("vidUrls:" + XDMUtils.appendArray2Str(this.vidUrls) + newLine);
			fw.write("fileExts:" + XDMUtils.appendArray2Str(this.fileExits) + newLine);
			fw.write("vidExts:" + XDMUtils.appendArray2Str(this.vidExits) + newLine);
			fw.write("proxyMode:" + this.proxyMode + newLine);
			fw.write("proxyPac:" + this.proxyPac + newLine);
			fw.write("proxyHost:" + this.proxyHost + newLine);
			fw.write("proxyPort:" + this.proxyPort + newLine);
			fw.write("socksHost:" + this.socksHost + newLine);
			fw.write("socksPort:" + this.socksPort + newLine);
			fw.write("proxyUser:" + this.proxyUser + newLine);
			fw.write("proxyPass:" + this.proxyPass + newLine);
			fw.write("autoShutdown:" + this.autoShutdown + newLine);
			fw.write("keepAwake:" + this.keepAwake + newLine);
			fw.write("execCmd:" + this.execCmd + newLine);
			fw.write("execAntivir:" + this.execAntivirus + newLine);
			fw.write("version:" + XDMApp.APP_VERSION + newLine);
			fw.write("autoStart:" + this.autoStart + newLine);
			fw.write("language:" + this.language + newLine);
			fw.write("downloadAutoStart:" + this.downloadAutoStart + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.antivirusExe))
				fw.write("antivirExe:" + this.antivirusExe + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.antivirusCmd))
				fw.write("antivirCmd:" + this.antivirusCmd + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.customCmd))
				fw.write("customCmd:" + this.customCmd + newLine);
			fw.write("showVideoNotification:" + this.showVideoNotification + newLine);
			fw.write("monitorClipboard:" + this.monitorClipboard + newLine);

			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryOther))
				fw.write("categoryOther:" + this.categoryOther + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryCompressed))
				fw.write("categoryCompressed:" + this.categoryCompressed + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryDocuments))
				fw.write("categoryDocuments:" + this.categoryDocuments + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryMusic))
				fw.write("categoryMusic:" + this.categoryMusic + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryVideos))
				fw.write("categoryVideos:" + this.categoryVideos + newLine);
			if (!StringUtils.isNullOrEmptyOrBlank(this.categoryPrograms))
				fw.write("categoryPrograms:" + this.categoryPrograms + newLine);
			fw.write("fetchTs:" + this.fetchTs + newLine);
			fw.write("noTransparency:" + this.noTransparency + newLine);
			fw.write("forceSingleFolder:" + this.forceSingleFolder + newLine);
			fw.write("hideTray:" + this.hideTray + newLine);
			if (this.lastFolder != null) {
				fw.write("lastFolder:" + this.lastFolder + newLine);
			}
			fw.write("showVideoListOnlyInBrowser:" + this.showVideoListOnlyInBrowser + newLine);
			fw.write("zoomLevelIndex:" + this.zoomLevelIndex + newLine);

		} catch (Exception e) {
			Logger.error(e);
		}
		IOUtils.closeFlow(fw);
	}

	public void load() {
		Logger.info("Loading config...");
		BufferedReader br = null;
		try {
			File file = new File(System.getProperty("user.home"), ".xdman/config.txt");
			if (!file.exists()) {
				return;
			}
			FileReader r = new FileReader(file);
			br = new BufferedReader(r);
			while (true) {
				String ln = br.readLine();
				if (ln == null)
					break;
				if (ln.startsWith("#"))
					continue;
				int index = ln.indexOf(":");
				if (index < 1)
					continue;
				String key = ln.substring(0, index);
				String val = ln.substring(index + 1);
				switch (key) {
				case "monitoring":
					this.monitoring = val.equals("true");
					break;
				case "downloadFolder":
					this.downloadFolder = val;
					break;
				case "temporaryFolder":
					this.temporaryFolder = val;
					break;
				case "maxSegments":
					this.maxSegments = Integer.parseInt(val);
					break;
				case "minSegmentSize2":
					this.minSegmentSize = Integer.parseInt(val);
					break;
				case "networkTimeout":
					this.networkTimeout = Integer.parseInt(val);
					break;
				case "tcpWindowSize2":
					this.tcpWindowSize = Integer.parseInt(val);
					break;
				case "duplicateAction":
					this.duplicateAction = Integer.parseInt(val);
					break;
				case "speedLimit":
					this.speedLimit = Integer.parseInt(val);
					break;
				case "showDownloadWindow":
					this.showDownloadWindow = val.equals("true");
					break;
				case "showDownloadCompleteWindow":
					this.showDownloadCompleteWindow = val.equals("true");
					break;
				case "downloadAutoStart":
					this.downloadAutoStart = val.equals("true");
					break;
				case "minVidSize":
					this.minVidSize = Integer.parseInt(val);
					break;
				case "parallalDownloads":
					this.parallelDownloads = Integer.parseInt(val);
					break;
				case "blockedHosts":
					this.blockedHosts = val.split(",");
					break;
				case "vidUrls":
					this.vidUrls = val.split(",");
					break;
				case "fileExts":
					this.fileExits = val.split(",");
					break;
				case "vidExts":
					this.vidExits = val.split(",");
					break;
				case "proxyMode":
					this.proxyMode = Integer.parseInt(val);
					break;
				case "proxyPort":
					this.proxyPort = Integer.parseInt(val);
					break;
				case "socksPort":
					this.socksPort = Integer.parseInt(val);
					break;
				case "proxyPac":
					this.proxyPac = val;
					break;
				case "proxyHost":
					this.proxyHost = val;
					break;
				case "socksHost":
					this.socksHost = val;
					break;
				case "proxyUser":
					this.proxyUser = val;
					break;
				case "proxyPass":
					this.proxyPass = val;
					break;
				case "showVideoNotification":
					this.showVideoNotification = "true".equals(val);
					break;
				case "keepAwake":
					this.keepAwake = "true".equals(val);
					break;
				case "autoStart":
					this.autoStart = "true".equals(val);
					break;
				case "execAntivir":
					this.execAntivirus = "true".equals(val);
					break;
				case "execCmd":
					this.execCmd = "true".equals(val);
					break;
				case "antivirExe":
					this.antivirusExe = val;
					break;
				case "antivirCmd":
					this.antivirusCmd = val;
					break;
				case "customCmd":
					this.customCmd = val;
					break;
				case "autoShutdown":
					this.autoShutdown = "true".equals(val);
					break;
				case "version":
					this.firstRun = !XDMApp.APP_VERSION.equals(val);
					break;
				case "language":
					this.language = val;
					break;
				case "monitorClipboard":
					this.monitorClipboard = "true".equals(val);
					break;
				case "categoryOther":
					this.categoryOther = val;
					break;
				case "categoryDocuments":
					this.categoryDocuments = val;
					break;
				case "categoryCompressed":
					this.categoryCompressed = val;
					break;
				case "categoryMusic":
					this.categoryMusic = val;
					break;
				case "categoryVideos":
					this.categoryVideos = val;
					break;
				case "categoryPrograms":
					this.categoryPrograms = val;
					break;
				case "fetchTs":
					this.fetchTs = "true".equals(val);
					break;
				case "noTransparency":
					this.noTransparency = "true".equals(val);
					break;
				case "forceSingleFolder":
					this.forceSingleFolder = "true".equals(val);
					break;
				case "hideTray":
					this.hideTray = "true".equals(val);
					break;
				case "lastFolder":
					this.lastFolder = val;
					break;
				case "showVideoListOnlyInBrowser":
					this.showVideoListOnlyInBrowser = "true".equals(val);
					break;
				case "zoomLevelIndex":
					this.zoomLevelIndex = Integer.parseInt(val);
					break;
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			if (!this.forceSingleFolder) {
				createFolders();
			}
			IOUtils.closeFlow(br);
		}
	}

	public void createFolders() {
		Logger.info("Creating folders");
		this.getCategoryDocuments();
		this.getCategoryMusic();
		this.getCategoryCompressed();
		this.getCategoryPrograms();
		this.getCategoryVideos();
	}

	public final String getMetadataFolder() {
		return this.metadataFolder;
	}

	public final String getTemporaryFolder() {
		return this.temporaryFolder;
	}

	public void setTemporaryFolder(String folder) {
		this.temporaryFolder = folder;
	}

	public final String getDataFolder() {
		return this.dataFolder;
	}

	public int getX() {
		return -1;
	}

	public int getY() {
		return -1;
	}

	public int getWidth() {
		return -1;
	}

	public int getHeight() {
		return -1;
	}

	public boolean getSortAsc() {
		return this.sortAsc;
	}

	public void setSortAsc(boolean sortAsc) {
		this.sortAsc = sortAsc;
	}

	public boolean isBrowserMonitoringEnabled() {
		return this.monitoring;
	}

	public void enableMonitoring(boolean enable) {
		this.monitoring = enable;
		for (MonitoringListener mon : this.listeners) {
			if (mon != null) {
				mon.configChanged();
			}
		}
	}

	public int getSortField() {
		return this.sortField;
	}

	public void setSortField(int sortField) {
		this.sortField = sortField;
	}

	public int getCategoryFilter() {
		return this.categoryFilter;
	}

	public void setCategoryFilter(int categoryFilter) {
		this.categoryFilter = categoryFilter;
	}

	public int getStateFilter() {
		return this.stateFilter;
	}

	public void setStateFilter(int stateFilter) {
		this.stateFilter = stateFilter;
	}

	public String getSearchText() {
		return this.searchText;
	}

	public void setSearchText(String searchText) {
		this.searchText = searchText;
	}

	public String getDownloadFolder() {
		return this.downloadFolder;
	}

	public void setDownloadFolder(String downloadFolder) {
		this.downloadFolder = downloadFolder;
	}

	public int getMaxSegments() {
		return this.maxSegments;
	}

	public void setMaxSegments(int maxSegments) {
		this.maxSegments = maxSegments;
	}

	public int getMinSegmentSize() {
		return this.minSegmentSize;
	}

	public void setMinSegmentSize(int minSegmentSize) {
		this.minSegmentSize = minSegmentSize;
	}

	public final int getSpeedLimit() {
		return this.speedLimit;
	}

	public final void setSpeedLimit(int speedLimit) {
		this.speedLimit = speedLimit;
	}

	public final boolean showDownloadWindow() {
		return this.showDownloadWindow;
	}

	public final void setShowDownloadWindow(boolean show) {
		this.showDownloadWindow = show;
	}

	public final int getMaxDownloads() {
		return this.parallelDownloads;
	}

	public final void setMaxDownloads(int maxDownloads) {
		this.parallelDownloads = maxDownloads;
	}

	public final boolean isAutoShutdown() {
		return this.autoShutdown;
	}

	public final void setAutoShutdown(boolean autoShutdown) {
		this.autoShutdown = autoShutdown;
	}

	public String[] getBlockedHosts() {
		return this.blockedHosts;
	}

	public void setBlockedHosts(String[] blockedHosts) {
		this.blockedHosts = blockedHosts;
	}

	public String[] getVidUrls() {
		return this.vidUrls;
	}

	public void setVidUrls(String[] vidUrls) {
		this.vidUrls = vidUrls;
	}

	public String[] getFileExits() {
		return this.fileExits;
	}

	public void setFileExits(String[] fileExits) {
		this.fileExits = fileExits;
	}

	public String[] getVidExits() {
		return this.vidExits;
	}

	public void setVidExits(String[] vidExits) {
		this.vidExits = vidExits;
	}

	public final boolean showDownloadCompleteWindow() {
		return showDownloadCompleteWindow;
	}

	public final int getDuplicateAction() {
		return this.duplicateAction;
	}

	public final void setDuplicateAction(int duplicateAction) {
		this.duplicateAction = duplicateAction;
	}

	public boolean isQuietMode() {
		return this.quietMode;
	}

	public void setQuietMode(boolean quietMode) {
		this.quietMode = quietMode;
	}

	public final void setShowDownloadCompleteWindow(boolean show) {
		this.showDownloadCompleteWindow = show;
	}

	public final String[] getDefaultFileTypes() {
		return this.defaultFileTypes;
	}

	public final void setDefaultFileTypes(String[] defaultFileTypes) {
		this.defaultFileTypes = defaultFileTypes;
	}

	public final String[] getDefaultVideoTypes() {
		return this.defaultVideoTypes;
	}

	public final void setDefaultVideoTypes(String[] defaultVideoTypes) {
		this.defaultVideoTypes = defaultVideoTypes;
	}

	public final int getNetworkTimeout() {
		return this.networkTimeout;
	}

	public final void setNetworkTimeout(int networkTimeout) {
		this.networkTimeout = networkTimeout;
	}

	public final int getTcpWindowSize() {
		return this.tcpWindowSize;
	}

	public final void setTcpWindowSize(int tcpWindowSize) {
		this.tcpWindowSize = tcpWindowSize;
	}

	public final int getProxyMode() {
		return this.proxyMode;
	}

	public final void setProxyMode(int proxyMode) {
		this.proxyMode = proxyMode;
	}

	public final String getProxyUser() {
		return this.proxyUser;
	}

	public final void setProxyUser(String proxyUser) {
		this.proxyUser = proxyUser;
	}

	public final String getProxyPass() {
		return this.proxyPass;
	}

	public final void setProxyPass(String proxyPass) {
		this.proxyPass = proxyPass;
	}

	public final String getProxyPac() {
		return this.proxyPac;
	}

	public final void setProxyPac(String proxyPac) {
		this.proxyPac = proxyPac;
	}

	public final String getProxyHost() {
		return this.proxyHost;
	}

	public final void setProxyHost(String proxyHost) {
		this.proxyHost = proxyHost;
	}

	public final int getProxyPort() {
		return this.proxyPort;
	}

	public final void setProxyPort(int proxyPort) {
		this.proxyPort = proxyPort;
	}

	public boolean isShowVideoNotification() {
		return this.showVideoNotification;
	}

	public void setShowVideoNotification(boolean showVideoNotification) {
		this.showVideoNotification = showVideoNotification;
	}

	public int getMinVidSize() {
		return this.minVidSize;
	}

	public void setMinVidSize(int minVidSize) {
		this.minVidSize = minVidSize;
	}

	public String getSocksHost() {
		return this.socksHost;
	}

	public void setSocksHost(String socksHost) {
		this.socksHost = socksHost;
	}

	public int getSocksPort() {
		return this.socksPort;
	}

	public void setSocksPort(int socksPort) {
		this.socksPort = socksPort;
	}

	public boolean isKeepAwake() {
		return this.keepAwake;
	}

	public void setKeepAwake(boolean keepAwake) {
		this.keepAwake = keepAwake;
	}

	public boolean isExecCmd() {
		return this.execCmd;
	}

	public void setExecCmd(boolean execCmd) {
		this.execCmd = execCmd;
	}

	public boolean isExecAntivirus() {
		return this.execAntivirus;
	}

	public void setExecAntivirus(boolean execAntivirus) {
		this.execAntivirus = execAntivirus;
	}

	public boolean isAutoStart() {
		return this.autoStart;
	}

	public void setAutoStart(boolean autoStart) {
		this.autoStart = autoStart;
	}

	public String getCustomCmd() {
		return this.customCmd;
	}

	public void setCustomCmd(String customCmd) {
		this.customCmd = customCmd;
	}

	public String getAntivirusCmd() {
		return this.antivirusCmd;
	}

	public void setAntivirusCmd(String antivirusCmd) {
		this.antivirusCmd = antivirusCmd;
	}

	public String getAntivirusExe() {
		return this.antivirusExe;
	}

	public void setAntivirusExe(String antivirusExe) {
		this.antivirusExe = antivirusExe;
	}

	public boolean isFirstRun() {
		return this.firstRun;
	}

	public boolean isMonitorClipboard() {
		return monitorClipboard;
	}

	public void setMonitorClipboard(boolean monitorClipboard) {
		this.monitorClipboard = monitorClipboard;
	}

	public void addBlockedHosts(String host) {
		List<String> list = new ArrayList<>(Arrays.asList(this.blockedHosts));
		if (list.contains(host)) {
			return;
		}
		list.add(host);
		this.blockedHosts = list.toArray(new String[list.size()]);
	}

	public String getCategoryOther() {
		if (this.categoryOther == null) {
			this.categoryOther = getDownloadFolder();
		}
		return this.categoryOther;
	}

	public void setCategoryOther(String categoryOther) {
		this.categoryOther = categoryOther;
	}

	public String getCategoryDocuments() {
		if (this.categoryDocuments == null) {
			File folder = new File(getDownloadFolder(), "Documents");
			folder.mkdirs();
			this.categoryDocuments = folder.getAbsolutePath();
		}
		return this.categoryDocuments;
	}

	public void setCategoryDocuments(String categoryDocuments) {
		this.categoryDocuments = categoryDocuments;
	}

	public String getCategoryMusic() {
		if (this.categoryMusic == null) {
			File folder = new File(getDownloadFolder(), "Music");
			folder.mkdirs();
			this.categoryMusic = folder.getAbsolutePath();
		}
		return this.categoryMusic;
	}

	public void setCategoryMusic(String categoryMusic) {
		this.categoryMusic = categoryMusic;
	}

	public String getCategoryVideos() {
		if (this.categoryVideos == null) {
			File folder = new File(getDownloadFolder(), "Video");
			folder.mkdirs();
			this.categoryVideos = folder.getAbsolutePath();
		}
		return this.categoryVideos;
	}

	public void setCategoryVideos(String categoryVideos) {
		this.categoryVideos = categoryVideos;
	}

	public String getCategoryPrograms() {
		if (this.categoryPrograms == null) {
			File folder = new File(getDownloadFolder(), "Programs");
			folder.mkdirs();
			this.categoryPrograms = folder.getAbsolutePath();
		}
		return this.categoryPrograms;
	}

	public void setCategoryPrograms(String categoryPrograms) {
		this.categoryPrograms = categoryPrograms;
	}

	public String getCategoryCompressed() {
		if (this.categoryCompressed == null) {
			File folder = new File(getDownloadFolder(), "Compressed");
			folder.mkdirs();
			this.categoryCompressed = folder.getAbsolutePath();
		}
		return this.categoryCompressed;
	}

	public void setCategoryCompressed(String categoryCompressed) {
		this.categoryCompressed = categoryCompressed;
	}

	public boolean isDownloadAutoStart() {
		return this.downloadAutoStart;
	}

	public void setDownloadAutoStart(boolean downloadAutoStart) {
		this.downloadAutoStart = downloadAutoStart;
	}

	public boolean isFetchTs() {
		return this.fetchTs;
	}

	public void setFetchTs(boolean fetchTs) {
		this.fetchTs = fetchTs;
	}

	public boolean isNoTransparency() {
		return this.noTransparency;
	}

	public void setNoTransparency(boolean noTransparency) {
		this.noTransparency = noTransparency;
	}

	public boolean isForceSingleFolder() {
		return this.forceSingleFolder;
	}

	public void setForceSingleFolder(boolean forceSingleFolder) {
		this.forceSingleFolder = forceSingleFolder;
	}

	public boolean isHideTray() {
		return this.hideTray;
	}

	public void setHideTray(boolean hideTray) {
		this.hideTray = hideTray;
	}

	public String getLastFolder() {
		return this.lastFolder;
	}

	public void setLastFolder(String lastFolder) {
		this.lastFolder = lastFolder;
	}

	public String getQueueIdFilter() {
		return this.queueIdFilter;
	}

	public void setQueueIdFilter(String queueIdFilter) {
		this.queueIdFilter = queueIdFilter;
	}

	public boolean isShowVideoListOnlyInBrowser() {
		return this.showVideoListOnlyInBrowser;
	}

	public void setShowVideoListOnlyInBrowser(boolean showVideoListOnlyInBrowser) {
		this.showVideoListOnlyInBrowser = showVideoListOnlyInBrowser;
	}

	public String[] getVidMime() {
		return this.vidMime;
	}

	public void setVidMime(String[] vidMime) {
		this.vidMime = vidMime;
	}

	public int getZoomLevelIndex() {
		return this.zoomLevelIndex;
	}

	public void setZoomLevelIndex(int zoomLevelIndex) {
		this.zoomLevelIndex = zoomLevelIndex;
	}
}
