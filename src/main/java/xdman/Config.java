package xdman;

import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Config {
    private static Config _config;
    private boolean forceSingleFolder;
    private boolean monitoring = true;
    private File metadataDir;
    private File temporaryDir;
    private File downloadDir;
    private File dataDir;
    private File configFile;
    private int sortField;
    private boolean sortAsc;
    private int categoryFilter;
    private int stateFilter;
    private String searchText;
    private int maxSegments;
    private int minSegmentSize;
    private int speedLimit; // in kb/sec
    private boolean showDownloadWindow;
    private boolean showDownloadCompleteWindow;
    private int parallelDownloads;
    private boolean autoShutdown;
    private int duplicateAction;
    private String[] blockedHosts;
    private String[] vidUrls;
    private String[] fileExts;
    private String[] vidExts;
    private String[] defaultFileTypes;
    private String[] defaultVideoTypes;
    private int networkTimeout;
    private int tcpWindowSize;
    private int proxyMode;// 0 no-proxy,1 pac, 2 http, 3 socks
    private String proxyPac;
    private String proxyHost;
    private String socksHost;
    private int proxyPort;
    private int socksPort;
    private String proxyUser;
    private String proxyPass;
    private boolean showVideoNotification;
    private int minVidSize;
    private boolean keepAwake;
    private boolean execCmd;
    private boolean execAntivirus;
    private boolean autoStart;
    private String customCmd;
    private String antivirusCmd;
    private String antivirusExe;
    private boolean firstRun;
    private String language;
    private boolean monitorClipboard;
    private File otherDir;
    private File documentsDir;
    private File musicDir;
    private File videosDir;
    private File programsDir;
    private File compressedDir;
    private boolean downloadAutoStart;
    private boolean fetchTs;
    private boolean noTransparency;
    private boolean hideTray;
    private String lastFolder;
    private List<MonitoringListener> listeners;
    private String queueIdFilter;

    private Config() {
        setForceSingleFolder(false);
        setDataDir(getDir(new File(System.getProperty("user.home"), ".xdman").getAbsolutePath()));
        setConfigFile(new File(getDataDir(), "config.txt"));
        setMetadataFolder(new File(getDataDir(), "metadata").getAbsolutePath());
        setTemporaryFolder(new File(getDataDir(), "temp").getAbsolutePath());
        setDownloadFolder(new File(XDMUtils.getDownloadsFolder()).getAbsolutePath());
        setDocumentsFolder(new File(getDownloadFolder(), "Documents").getAbsolutePath());
        setMusicFolder(new File(getDownloadFolder(), "Music").getAbsolutePath());
        setVideosFolder(new File(getDownloadFolder(), "Video").getAbsolutePath());
        setProgramsFolder(new File(getDownloadFolder(), "Programs").getAbsolutePath());
        setCompressedFolder(new File(getDownloadFolder(), "Compressed").getAbsolutePath());
        setOtherFolder(getDownloadFolder());
        this.setMonitoring(true);
        this.setShowDownloadWindow(true);
        this.setMaxSegments(8);
        this.setMinSegmentSize(256 * 1024);
        this.setParallelDownloads(100);
        this.setMinVidSize(1 * 1024 * 1024);
        this.setDefaultFileTypes(new String[]{"3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "GZ", "ISO",
                "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ"});
        this.setFileExts(getDefaultFileTypes());
        this.setAutoShutdown(false);
        this.setBlockedHosts(new String[]{"update.microsoft.com", "windowsupdate.com", "thwawte.com"});
        this.setDefaultVideoTypes(new String[]{"MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
                "MOV", "MPG", "MPEG", "OPUS"});
        this.setVidExts(getDefaultVideoTypes());
        this.setVidUrls(new String[]{".facebook.com|pagelet", "player.vimeo.com/", "instagram.com/p/"});
        this.setNetworkTimeout(60);
        this.setTcpWindowSize(0);
        this.setSpeedLimit(0);
        this.setProxyMode(0);
        this.setProxyPort(0);
        this.setSocksPort(0);
        this.setSocksHost("");
        this.setProxyPass(this.getSocksHost());
        this.setProxyUser(this.getSocksHost());
        this.setProxyHost(this.getSocksHost());
        this.setProxyPac(this.getSocksHost());
        this.setShowVideoNotification(true);
        this.setShowDownloadCompleteWindow(true);
        this.setFirstRun(true);
        this.setLanguage("en");
        this.setMonitorClipboard(false);
        this.setNoTransparency(false);
        this.setHideTray(true);
        this.setListeners(new ArrayList<>());
    }

    private static Config get_config() {
        return _config;
    }

    private static void set_config(Config _config) {
        Config._config = _config;
    }

    public static Config getInstance() {
        if (get_config() == null) {
            set_config(new Config());
        }
        return get_config();
    }

    private static File getDir(String folder) {
        if (folder == null) {
            return null;
        }
        File dir = new File(folder);
        if (!dir.exists()) {
            dir.mkdir();
        }
        return dir;
    }

    public void addConfigListener(MonitoringListener listener) {
        getListeners().add(listener);
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void save() {
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(getConfigFile());

            String newLine = "\n";

            fileWriter.write("monitoring:" + this.isMonitoring() + newLine);
            fileWriter.write("downloadFolder:" + getDownloadFolder() + newLine);
            fileWriter.write("temporaryFolder:" + getTemporaryFolder() + newLine);
            fileWriter.write("parallelDownloads:" + this.getParallelDownloads() + newLine);
            fileWriter.write("maxSegments:" + this.getMaxSegments() + newLine);
            fileWriter.write("networkTimeout:" + this.getNetworkTimeout() + newLine);
            fileWriter.write("tcpWindowSize2:" + this.getTcpWindowSize() + newLine);
            fileWriter.write("minSegmentSize2:" + this.getMinSegmentSize() + newLine);
            fileWriter.write("minVidSize:" + this.getMinVidSize() + newLine);
            fileWriter.write("duplicateAction:" + this.getDuplicateAction() + newLine);
            fileWriter.write("speedLimit:" + this.getSpeedLimit() + newLine);
            fileWriter.write("showDownloadWindow:" + this.isShowDownloadWindow() + newLine);
            fileWriter.write("showDownloadCompleteWindow:" + this.isShowDownloadCompleteWindow() + newLine);
            fileWriter.write("blockedHosts:" + XDMUtils.appendArray2Str(this.getBlockedHosts()) + newLine);
            fileWriter.write("vidUrls:" + XDMUtils.appendArray2Str(this.getVidUrls()) + newLine);
            fileWriter.write("fileExts:" + XDMUtils.appendArray2Str(this.getFileExts()) + newLine);
            fileWriter.write("vidExts:" + XDMUtils.appendArray2Str(this.getVidExts()) + newLine);

            fileWriter.write("proxyMode:" + this.getProxyMode() + newLine);
            fileWriter.write("proxyPac:" + this.getProxyPac() + newLine);
            fileWriter.write("proxyHost:" + this.getProxyHost() + newLine);
            fileWriter.write("proxyPort:" + this.getProxyPort() + newLine);
            fileWriter.write("socksHost:" + this.getSocksHost() + newLine);
            fileWriter.write("socksPort:" + this.getSocksPort() + newLine);
            fileWriter.write("proxyUser:" + this.getProxyUser() + newLine);
            fileWriter.write("proxyPass:" + this.getProxyPass() + newLine);
            fileWriter.write("autoShutdown:" + this.isAutoShutdown() + newLine);
            fileWriter.write("keepAwake:" + this.isKeepAwake() + newLine);
            fileWriter.write("execCmd:" + this.isExecCmd() + newLine);
            fileWriter.write("execAntivir:" + this.isExecAntivirus() + newLine);
            fileWriter.write("version:" + XDMApp.APP_VERSION + newLine);
            fileWriter.write("autoStart:" + this.isAutoStart() + newLine);
            fileWriter.write("language:" + this.getLanguage() + newLine);
            fileWriter.write("downloadAutoStart:" + this.isDownloadAutoStart() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(this.getAntivirusExe()))
                fileWriter.write("antivirExe:" + this.getAntivirusExe() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(this.getAntivirusCmd()))
                fileWriter.write("antivirCmd:" + this.getAntivirusCmd() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(this.getCustomCmd()))
                fileWriter.write("customCmd:" + this.getCustomCmd() + newLine);
            fileWriter.write("showVideoNotification:" + this.isShowVideoNotification() + newLine);
            fileWriter.write("monitorClipboard:" + this.isMonitorClipboard() + newLine);

            if (!StringUtils.isNullOrEmptyOrBlank(getOtherFolder()))
                fileWriter.write("categoryOther:" + getOtherFolder() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(getCompressedFolder()))
                fileWriter.write("compressedFolder:" + getCompressedFolder() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(getDocumentsFolder()))
                fileWriter.write("documentsFolder:" + getDocumentsFolder() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(getMusicFolder()))
                fileWriter.write("musicFolder:" + getMusicFolder() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(getVideosFolder()))
                fileWriter.write("videosFolder:" + getVideosFolder() + newLine);
            if (!StringUtils.isNullOrEmptyOrBlank(getProgramsFolder()))
                fileWriter.write("programsFolder:" + getProgramsFolder() + newLine);
            fileWriter.write("fetchTs:" + this.isFetchTs() + newLine);
            fileWriter.write("noTransparency:" + this.isNoTransparency() + newLine);
            fileWriter.write("forceSingleFolder:" + this.isForceSingleFolder() + newLine);
            fileWriter.write("hideTray:" + this.isHideTray() + newLine);
            if (getLastFolder() != null) {
                fileWriter.write("lastFolder:" + this.getLastFolder() + newLine);
            }

        } catch (Exception e) {
        }
        try {
            if (fileWriter != null)
                fileWriter.close();
        } catch (Exception e) {
        }
    }

    public void load() {
        Logger.log("Loading config...");
        BufferedReader br = null;
        try {
            if (!getConfigFile().exists()) {
                return;
            }
            FileReader r = new FileReader(getConfigFile());
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
                if (key.equals("monitoring")) {
                    this.setMonitoring(val.equals("true"));
                } else if (key.equals("downloadFolder")) {
                    setDownloadFolder(val);
                } else if (key.equals("temporaryFolder")) {
                    setTemporaryFolder(val);
                } else if (key.equals("maxSegments")) {
                    this.setMaxSegments(Integer.parseInt(val));
                } else if (key.equals("minSegmentSize2")) {
                    this.setMinSegmentSize(Integer.parseInt(val));
                } else if (key.equals("networkTimeout")) {
                    this.setNetworkTimeout(Integer.parseInt(val));
                } else if (key.equals("tcpWindowSize2")) {
                    this.setTcpWindowSize(Integer.parseInt(val));
                } else if (key.equals("duplicateAction")) {
                    this.setDuplicateAction(Integer.parseInt(val));
                } else if (key.equals("speedLimit")) {
                    this.setSpeedLimit(Integer.parseInt(val));
                } else if (key.equals("showDownloadWindow")) {
                    this.setShowDownloadWindow(val.equals("true"));
                } else if (key.equals("showDownloadCompleteWindow")) {
                    this.setShowDownloadCompleteWindow(val.equals("true"));
                } else if (key.equals("downloadAutoStart")) {
                    this.setDownloadAutoStart(val.equals("true"));
                } else if (key.equals("minVidSize")) {
                    this.setMinVidSize(Integer.parseInt(val));
                } else if (key.equals("parallelDownloads")) {
                    this.setParallelDownloads(Integer.parseInt(val));
                } else if (key.equals("blockedHosts")) {
                    this.setBlockedHosts(val.split(","));
                } else if (key.equals("vidUrls")) {
                    this.setVidUrls(val.split(","));
                } else if (key.equals("fileExts")) {
                    this.setFileExts(val.split(","));
                } else if (key.equals("vidExts")) {
                    this.setVidExts(val.split(","));
                } else if (key.equals("proxyMode")) {
                    this.setProxyMode(Integer.parseInt(val));
                } else if (key.equals("proxyPort")) {
                    this.setProxyPort(Integer.parseInt(val));
                } else if (key.equals("socksPort")) {
                    this.setSocksPort(Integer.parseInt(val));
                } else if (key.equals("proxyPac")) {
                    this.setProxyPac(val);
                } else if (key.equals("proxyHost")) {
                    this.setProxyHost(val);
                } else if (key.equals("socksHost")) {
                    this.setSocksHost(val);
                } else if (key.equals("proxyUser")) {
                    this.setProxyUser(val);
                } else if (key.equals("proxyPass")) {
                    this.setProxyPass(val);
                } else if (key.equals("showVideoNotification")) {
                    this.setShowVideoNotification("true".equals(val));
                } else if (key.equals("keepAwake")) {
                    this.setKeepAwake("true".equals(val));
                } else if (key.equals("autoStart")) {
                    this.setAutoStart("true".equals(val));
                } else if (key.equals("execAntivir")) {
                    this.setExecAntivirus("true".equals(val));
                } else if (key.equals("execCmd")) {
                    this.setExecCmd("true".equals(val));
                } else if (key.equals("antivirExe")) {
                    this.setAntivirusExe(val);
                } else if (key.equals("antivirCmd")) {
                    this.setAntivirusCmd(val);
                } else if (key.equals("customCmd")) {
                    this.setCustomCmd(val);
                } else if (key.equals("autoShutdown")) {
                    this.setAutoShutdown("true".equals(val));
                } else if (key.equals("version")) {
                    this.setFirstRun(!XDMApp.APP_VERSION.equals(val));
                } else if (key.equals("language")) {
                    this.setLanguage(val);
                } else if (key.equals("monitorClipboard")) {
                    this.setMonitorClipboard("true".equals(val));
                } else if (key.equals("categoryOther")) {
                    setOtherFolder(val);
                } else if (key.equals("documentsFolder")) {
                    setDocumentsFolder(val);
                } else if (key.equals("compressedFolder")) {
                    setCompressedFolder(val);
                } else if (key.equals("musicFolder")) {
                    setMusicFolder(val);
                } else if (key.equals("videosFolder")) {
                    setVideosFolder(val);
                } else if (key.equals("programsFolder")) {
                    setProgramsFolder(val);
                } else if (key.equals("fetchTs")) {
                    this.setFetchTs("true".equals(val));
                } else if (key.equals("noTransparency")) {
                    this.setNoTransparency("true".equals(val));
                } else if (key.equals("forceSingleFolder")) {
                    this.setForceSingleFolder("true".equals(val));
                } else if (key.equals("hideTray")) {
                    this.setHideTray("true".equals(val));
                } else if (key.equals("lastFolder")) {
                    this.setLastFolder(val);
                }
            }
        } catch (Exception e) {
            Logger.log(e);
        } finally {
            if (!isForceSingleFolder()) {
                createFolders();
            }
            try {
                br.close();
            } catch (Exception e) {
            }
        }
    }

    public void createFolders() {
        Logger.log("Creating folders");
        getDocumentsFolder();
        getMusicFolder();
        getCompressedFolder();
        getProgramsFolder();
        getVideosFolder();
    }

    public final String getMetadataFolder() {
        if (getMetadataDir() == null) {
            setMetadataFolder(new File(getDataDir(), "metadata").getAbsolutePath());
        }
        return getMetadataDir().getAbsolutePath();
    }

    private void setMetadataFolder(String metadataFolder) {
        this.setMetadataDir(getDir(metadataFolder));
    }

    public final String getDataFolder() {
        return getDataDir().getAbsolutePath();
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
        return isSortAsc();
    }

    public boolean isBrowserMonitoringEnabled() {
        return isMonitoring();
    }

    public void enableMonitoring(boolean enable) {
        setMonitoring(enable);
        for (MonitoringListener mon : getListeners()) {
            if (mon != null) {
                mon.configChanged();
            }
        }
    }

    public int getSortField() {
        return sortField;
    }

    public void setSortField(int sortField) {
        this.sortField = sortField;
    }

    public int getCategoryFilter() {
        return categoryFilter;
    }

    public void setCategoryFilter(int categoryFilter) {
        this.categoryFilter = categoryFilter;
    }

    public int getStateFilter() {
        return stateFilter;
    }

    public void setStateFilter(int stateFilter) {
        this.stateFilter = stateFilter;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public String getDownloadFolder() {
        if (getDownloadDir() == null) {
            setDownloadFolder(new File(XDMUtils.getDownloadsFolder()).getAbsolutePath());
        }
        return getDownloadDir().getAbsolutePath();
    }

    public void setDownloadFolder(String downloadFolder) {
        this.setDownloadDir(getDir(downloadFolder));
    }

    public int getMaxSegments() {
        return maxSegments;
    }

    public void setMaxSegments(int maxSegments) {
        this.maxSegments = maxSegments;
    }

    public int getMinSegmentSize() {
        return minSegmentSize;
    }

    private void setMinSegmentSize(int minSegmentSize) {
        this.minSegmentSize = minSegmentSize;
    }

    public final int getSpeedLimit() {
        return speedLimit;
    }

    public final void setSpeedLimit(int speedLimit) {
        this.speedLimit = speedLimit;
    }

    public final boolean showDownloadWindow() {
        return isShowDownloadWindow();
    }

    public final int getMaxDownloads() {
        return getParallelDownloads();
    }

    public final void setMaxDownloads(int maxDownloads) {
        this.setParallelDownloads(maxDownloads);
    }

    public final boolean isAutoShutdown() {
        return autoShutdown;
    }

    public final void setAutoShutdown(boolean autoShutdown) {
        this.autoShutdown = autoShutdown;
    }

    public String[] getBlockedHosts() {
        return blockedHosts;
    }

    public void setBlockedHosts(String[] blockedHosts) {
        this.blockedHosts = blockedHosts;
    }

    public String[] getVidUrls() {
        return vidUrls;
    }

    private void setVidUrls(String[] vidUrls) {
        this.vidUrls = vidUrls;
    }

    public String[] getFileExts() {
        return fileExts;
    }

    public void setFileExts(String[] fileExts) {
        this.fileExts = fileExts;
    }

    public String[] getVidExts() {
        return vidExts;
    }

    public void setVidExts(String[] vidExts) {
        this.vidExts = vidExts;
    }

    public final boolean showDownloadCompleteWindow() {
        return isShowDownloadCompleteWindow();
    }

    public final int getDuplicateAction() {
        return duplicateAction;
    }

    public final void setDuplicateAction(int duplicateAction) {
        this.duplicateAction = duplicateAction;
    }

    public final String[] getDefaultFileTypes() {
        return defaultFileTypes;
    }

    private final void setDefaultFileTypes(String[] defaultFileTypes) {
        this.defaultFileTypes = defaultFileTypes;
    }

    public final String[] getDefaultVideoTypes() {
        return defaultVideoTypes;
    }

    private final void setDefaultVideoTypes(String[] defaultVideoTypes) {
        this.defaultVideoTypes = defaultVideoTypes;
    }

    public final int getNetworkTimeout() {
        return networkTimeout;
    }

    public final void setNetworkTimeout(int networkTimeout) {
        this.networkTimeout = networkTimeout;
    }

    public final int getTcpWindowSize() {
        return tcpWindowSize;
    }

    public final void setTcpWindowSize(int tcpWindowSize) {
        this.tcpWindowSize = tcpWindowSize;
    }

    public final int getProxyMode() {
        return proxyMode;
    }

    public final void setProxyMode(int proxyMode) {
        this.proxyMode = proxyMode;
    }

    public final String getProxyUser() {
        return proxyUser;
    }

    public final void setProxyUser(String proxyUser) {
        this.proxyUser = proxyUser;
    }

    public final String getProxyPass() {
        return proxyPass;
    }

    public final void setProxyPass(String proxyPass) {
        this.proxyPass = proxyPass;
    }

    public final String getProxyPac() {
        return proxyPac;
    }

    public final void setProxyPac(String proxyPac) {
        this.proxyPac = proxyPac;
    }

    public final String getProxyHost() {
        return proxyHost;
    }

    public final void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public final int getProxyPort() {
        return proxyPort;
    }

    public final void setProxyPort(int proxyPort) {
        this.proxyPort = proxyPort;
    }

    public boolean isShowVideoNotification() {
        return showVideoNotification;
    }

    public void setShowVideoNotification(boolean showVideoNotification) {
        this.showVideoNotification = showVideoNotification;
    }

    public int getMinVidSize() {
        return minVidSize;
    }

    public void setMinVidSize(int minVidSize) {
        this.minVidSize = minVidSize;
    }

    public String getSocksHost() {
        return socksHost;
    }

    public void setSocksHost(String socksHost) {
        this.socksHost = socksHost;
    }

    public int getSocksPort() {
        return socksPort;
    }

    public void setSocksPort(int socksPort) {
        this.socksPort = socksPort;
    }

    public boolean isKeepAwake() {
        return keepAwake;
    }

    public void setKeepAwake(boolean keepAwake) {
        this.keepAwake = keepAwake;
    }

    public boolean isExecCmd() {
        return execCmd;
    }

    public void setExecCmd(boolean execCmd) {
        this.execCmd = execCmd;
    }

    public boolean isExecAntivirus() {
        return execAntivirus;
    }

    public void setExecAntivirus(boolean execAntivirus) {
        this.execAntivirus = execAntivirus;
    }

    private boolean isAutoStart() {
        return autoStart;
    }

    private void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public String getCustomCmd() {
        return customCmd;
    }

    public void setCustomCmd(String customCmd) {
        this.customCmd = customCmd;
    }

    public String getAntivirusCmd() {
        return antivirusCmd;
    }

    public void setAntivirusCmd(String antivirusCmd) {
        this.antivirusCmd = antivirusCmd;
    }

    public String getAntivirusExe() {
        return antivirusExe;
    }

    public void setAntivirusExe(String antivirusExe) {
        this.antivirusExe = antivirusExe;
    }

    String getAntivirusExeCmd() {
        String antivirusExe = getAntivirusExe();
        String antivirusCmd = getAntivirusCmd() == null
                ? ""
                : getAntivirusCmd();
        return String.format("%s %s", antivirusExe,
                antivirusCmd);
    }

    public boolean isFirstRun() {
        return firstRun;
    }

    private void setFirstRun(boolean firstRun) {
        this.firstRun = firstRun;
    }

    public boolean isMonitorClipboard() {
        return monitorClipboard;
    }

    public void setMonitorClipboard(boolean monitorClipboard) {
        this.monitorClipboard = monitorClipboard;
    }

    public void addBlockedHosts(String host) {
        List<String> list = new ArrayList<>(Arrays.asList(getBlockedHosts()));
        if (list.contains(host)) {
            return;
        }
        list.add(host);
        setBlockedHosts(list.toArray(new String[list.size()]));
    }

    public String getOtherFolder() {
        if (this.getOtherDir() == null) {
            setOtherFolder(getDownloadFolder());
        }
        return this.getOtherDir().getAbsolutePath();
    }

    public void setOtherFolder(String otherFolder) {
        this.setOtherDir(getDir(otherFolder));
    }

    public String getDocumentsFolder() {
        if (this.getDocumentsDir() == null) {
            setDocumentsFolder(new File(getDownloadFolder(), "Documents").getAbsolutePath());
        }
        return getDocumentsDir().getAbsolutePath();
    }

    public void setDocumentsFolder(String documentsFolder) {
        this.setDocumentsDir(getDir(documentsFolder));
    }

    public String getMusicFolder() {
        if (this.getMusicDir() == null) {
            setMusicFolder(new File(getDownloadFolder(), "Music").getAbsolutePath());
            getMusicDir().mkdirs();
        }
        return getMusicDir().getAbsolutePath();
    }

    public void setMusicFolder(String musicFolder) {
        this.setMusicDir(getDir(musicFolder));
    }

    public String getVideosFolder() {
        if (this.getVideosDir() == null) {
            setVideosFolder(new File(getDownloadFolder(), "Video").getAbsolutePath());
        }
        return getVideosDir().getAbsolutePath();
    }

    public void setVideosFolder(String videosFolder) {
        this.setVideosDir(getDir(videosFolder));
    }

    public final String getTemporaryFolder() {
        if (this.getTemporaryDir() == null) {
            setTemporaryFolder(new File(getDataDir(), "temp").getAbsolutePath());
        }
        return getTemporaryDir().getAbsolutePath();
    }

    public void setTemporaryFolder(String temporaryFolder) {
        this.setTemporaryDir(getDir(temporaryFolder));
    }

    public String getProgramsFolder() {
        if (this.getProgramsDir() == null) {
            setProgramsFolder(new File(getDownloadFolder(), "Programs").getAbsolutePath());
        }
        return getProgramsDir().getAbsolutePath();
    }

    public void setProgramsFolder(String programsFolder) {
        this.setProgramsDir(getDir(programsFolder));
    }

    public String getCompressedFolder() {
        if (this.getCompressedDir() == null) {
            setCompressedFolder(new File(getDownloadFolder(), "Compressed").getAbsolutePath());
        }
        return getCompressedDir().getAbsolutePath();
    }

    public void setCompressedFolder(String compressedFolder) {
        this.setCompressedDir(getDir(compressedFolder));
    }

    public boolean isDownloadAutoStart() {
        return downloadAutoStart;
    }

    public void setDownloadAutoStart(boolean downloadAutoStart) {
        this.downloadAutoStart = downloadAutoStart;
    }

    public boolean isFetchTs() {
        return fetchTs;
    }

    public void setFetchTs(boolean fetchTs) {
        this.fetchTs = fetchTs;
    }

    public boolean isNoTransparency() {
        return noTransparency;
    }

    public void setNoTransparency(boolean noTransparency) {
        this.noTransparency = noTransparency;
    }

    public boolean isForceSingleFolder() {
        return forceSingleFolder;
    }

    public void setForceSingleFolder(boolean forceSingleFolder) {
        this.forceSingleFolder = forceSingleFolder;
    }

    public boolean isHideTray() {
        return hideTray;
    }

    public void setHideTray(boolean hideTray) {
        this.hideTray = hideTray;
    }

    public String getLastFolder() {
        return lastFolder;
    }

    public void setLastFolder(String lastFolder) {
        this.lastFolder = lastFolder;
    }

    public String getQueueIdFilter() {
        return queueIdFilter;
    }

    public void setQueueIdFilter(String queueIdFilter) {
        this.queueIdFilter = queueIdFilter;
    }

    private File getDataDir() {
        return dataDir;
    }

    public void setDataDir(File dataDir) {
        this.dataDir = dataDir;
    }

    private File getConfigFile() {
        return configFile;
    }

    public void setConfigFile(File configFile) {
        this.configFile = configFile;
    }

    public boolean isMonitoring() {
        return monitoring;
    }

    public void setMonitoring(boolean monitoring) {
        this.monitoring = monitoring;
    }

    private File getMetadataDir() {
        return metadataDir;
    }

    private void setMetadataDir(File metadataDir) {
        this.metadataDir = metadataDir;
    }

    private File getTemporaryDir() {
        return temporaryDir;
    }

    private void setTemporaryDir(File temporaryDir) {
        this.temporaryDir = temporaryDir;
    }

    private File getDownloadDir() {
        return downloadDir;
    }

    private void setDownloadDir(File downloadDir) {
        this.downloadDir = downloadDir;
    }

    private boolean isSortAsc() {
        return sortAsc;
    }

    public void setSortAsc(boolean sortAsc) {
        this.sortAsc = sortAsc;
    }

    private boolean isShowDownloadWindow() {
        return showDownloadWindow;
    }

    public final void setShowDownloadWindow(boolean show) {
        this.showDownloadWindow = show;
    }

    private boolean isShowDownloadCompleteWindow() {
        return showDownloadCompleteWindow;
    }

    public final void setShowDownloadCompleteWindow(boolean show) {
        this.showDownloadCompleteWindow = show;
    }

    private int getParallelDownloads() {
        return parallelDownloads;
    }

    private void setParallelDownloads(int parallelDownloads) {
        this.parallelDownloads = parallelDownloads;
    }

    private File getOtherDir() {
        return otherDir;
    }

    private void setOtherDir(File otherDir) {
        this.otherDir = otherDir;
    }

    private File getDocumentsDir() {
        return documentsDir;
    }

    private void setDocumentsDir(File documentsDir) {
        this.documentsDir = documentsDir;
    }

    private File getMusicDir() {
        return musicDir;
    }

    private void setMusicDir(File musicDir) {
        this.musicDir = musicDir;
    }

    private File getVideosDir() {
        return videosDir;
    }

    private void setVideosDir(File videosDir) {
        this.videosDir = videosDir;
    }

    private File getProgramsDir() {
        return programsDir;
    }

    private void setProgramsDir(File programsDir) {
        this.programsDir = programsDir;
    }

    private List<MonitoringListener> getListeners() {
        return listeners;
    }

    private void setListeners(List<MonitoringListener> listeners) {
        this.listeners = listeners;
    }

    private File getCompressedDir() {
        return compressedDir;
    }

    private void setCompressedDir(File compressedDir) {
        this.compressedDir = compressedDir;
    }
}
