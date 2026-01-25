//package xdman;
//
//import java.awt.EventQueue;
//import java.io.File;
//import java.net.PasswordAuthentication;
//import java.nio.file.Paths;
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
//import javax.swing.JOptionPane;
//import javax.swing.JPasswordField;
//import javax.swing.JTextField;
//import javax.swing.SwingUtilities;
//
//import xdm.app.AppContext;
//import xdm.app.constants.DownloadEntryState;
//import xdm.core.DownloadProgressListener;
//
//import xdm.core.constants.ErrorCode;
//import xdm.core.downloaders.AbstractDownloader;
//import xdm.core.downloaders.Metadata;
//import xdm.core.downloaders.http.HttpDownloader;
//import xdm.core.downloaders.http.HttpSource;
//import xdman.monitoring.BrowserMonitor;
//import xdman.network.http.HttpContext;
//import xdman.ui.components.DownloadWindow;
//import xdm.app.ui.screens.AppWindow;
//import xdman.ui.components.NewDownloadWindow;
//import xdman.ui.components.TrayHandler;
//// import xdman.ui.components.VideoDownloadWindow;
//// import xdman.ui.components.VideoPopup;
//// import xdman.ui.components.VideoPopupItem;
//import xdman.ui.res.StringResource;
//// import xdman.util.FFmpegDownloader;
//import xdman.util.LinuxUtils;
//import xdman.util.Logger;
//import xdman.util.MacUtils;
//import xdman.util.ParamUtils;
//import xdman.util.StringUtils;
//import xdman.util.WinUtils;
//import xdman.util.XDMUtils;
//
//public class XDMApp implements DownloadProgressListener, DownloadWindowListener {
//  public static final String GLOBAL_LOCK_FILE = ".xdm-global-lock";
//  public static final String APP_VERSION = "7.2.11";
//  public static final String XDM_WINDOW_TITLE = "XDM";
//  public static final String APP_UPDAT_URL =
//      "https://api.github.com/repos/subhra74/xdm/releases/latest";
//  public static final String APP_UPDATE_CHK_URL =
//      "https://subhra74.github.io/xdm/update-checker.html?v=";
//  public static final String APP_WIKI_URL = "https://github.com/subhra74/xdm/wiki";
//  public static final String APP_HOME_URL = "https://github.com/subhra74/xdm";
//  public static final String APP_TWITTER_URL = "https://twitter.com/XDM_subhra74";
//  public static final String APP_FACEBOOK_URL = "https://www.facebook.com/XDM.subhra74/";
//  public static final String[] ZOOM_LEVEL_STRINGS = {
//    "Default", "50%", "75%", "100%", "125%", "150%", "200%", "250%", "300%", "350%", "400%", "450%",
//    "500%"
//  };
//  public static final double[] ZOOM_LEVEL_VALUES = {
//    -1, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0
//  };
//
//  private ArrayList<ListChangeListener> listChangeListeners;
//  // private Map<Long, DownloadEntry> downloads;
//  private static XDMApp _this;
//  private Map<Long, AbstractDownloader> downloaders;
//  private Map<Long, DownloadWindow> downloadWindows;
//  private long lastSaved;
//  private QueueManager qMgr;
//  // private LinkRefreshCallback refreshCallback;
//
//  private ArrayList<Long> pendingDownloads; // this buffer is used when there
//  // is a limit on maximum
//  // simultaneous downloads and
//  // more downloads are started
//  // than permissible limit. If
//  // queues are also running then
//  // this buffer will be processed
//  // first
//  private static HashMap<String, String> paramMap;
//  private AppWindow appWindow;
//  private int pendingNotification = -1; // if main window in not created
//
//  // notification is stored in this
//  // variable
//
//  // private ArrayList<VideoPopupItem> itemList = new ArrayList<>();
//
//  public static void instanceStarted() {
//    Logger.log("instance starting...");
//    final XDMApp app = XDMApp.getInstance();
//    EventQueue.invokeLater(
//        new Runnable() {
//          @Override
//          public void run() {
//            if (!paramMap.containsKey("background")) {
//              Logger.log("showing main window.");
//              app.showMainWindow();
//            }
//            TrayHandler.createTray();
//            // if (XDMUtils.detectOS() != XDMUtils.LINUX) {
//            // TrayHandler.createTray();
//            // }
//          }
//        });
//    //    if (Config.getInstance().isFirstRun()) {
//    ////      if (XDMUtils.detectOS() != XDMUtils.WINDOWS) {
//    ////        XDMUtils.addToStartup();
//    ////      }
//    ////      NativeMessagingHostInstaller.installNativeMessagingHostForChrome();
//    ////      NativeMessagingHostInstaller.installNativeMessagingHostForFireFox();
//    ////      NativeMessagingHostInstaller.installNativeMessagingHostForChromium();
//    //    }
//    Logger.log("instance started.");
//  }
//
//  public static void instanceAlreadyRunning() {
//    Logger.log("instance already runninng");
//    ParamUtils.sendParam(paramMap);
//    System.exit(0);
//  }
//
//  public static void start(String args[]) {
//
//    Config.getInstance().load();
//    if (Config.getInstance().getZoomLevelIndex() > 0) {
//      //			double zoom = XDMApp.ZOOM_LEVEL_VALUES[Config.getInstance().getZoomLevelIndex()];
//      //			System.out.println("Zoom index; " + Config.getInstance().getZoomLevelIndex() + " " +
//      // zoom);
//      //
//      //			System.setProperty("sun.java2d.uiScale.enabled", "true");
//      //			System.setProperty("sun.java2d.uiScale", String.format("%.2f", zoom));
//    }
//
//    paramMap = new HashMap<>();
//    boolean expect = false;
//    boolean winInstall = false;
//    String key = null;
//    for (int i = 0; i < args.length; i++) {
//      if (expect) {
//        if (key != null) {
//          paramMap.put(key, args[i]);
//        }
//        expect = false;
//        continue;
//      }
//      if ("-u".equals(args[i])) {
//        key = "url";
//        expect = true;
//      } else if ("-m".equals(args[i])) {
//        paramMap.put("background", "true");
//        expect = false;
//      } else if ("-i".equals(args[i])) {
//        paramMap.put("installer", "true");
//        expect = false;
//        winInstall = true;
//      } else if ("-s".equals(args[i])) {
//        key = "screen";
//        expect = true;
//      } else if ("-o".equals(args[i]) || "--output".equals(args[i])) {
//        key = "output";
//        expect = true;
//      } else if ("-q".equals(args[i]) || "--quiet".equals(args[i])) {
//        paramMap.put("quiet", "true");
//        expect = false;
//      }
//    }
//
//    Logger.log("starting monitoring...");
//    BrowserMonitor.getInstance().startMonitoring();
//  }
//
//  public void showMainWindow() {
////    if (appWindow == null) {
////      appWindow = new AppWindow();
////    }
////    appWindow.setVisible(true);
////    appWindow.toFront();
//  }
//
//  private XDMApp() {
//    Logger.log("Init app");
//    //		String stype = paramMap.get("screen");
//    //		if (stype != null) {
//    //			if ("xxhdpi".equals(stype)) {
//    //				XDMUtils.forceScreenType(XDMConstants.XHDPI);
//    //			} else if ("xhdpi".equals(stype)) {
//    //				XDMUtils.forceScreenType(XDMConstants.HDPI);
//    //			} else if ("hdpi".equals(stype)) {
//    //				XDMUtils.forceScreenType(XDMConstants.NORMAL);
//    //			}
//    //		}
//    //		try {
//    //			UIManager.setLookAndFeel(new XDMLookAndFeel());
//    //		} catch (Exception e) {
//    //			Logger.log(e);
//    //		}
//
//    Config.getInstance().setAutoShutdown(false);
//    listChangeListeners = new ArrayList<>();
//    // downloads = new ConcurrentHashMap<>();
//    downloaders = new ConcurrentHashMap<>();
//    downloadWindows = new ConcurrentHashMap<>();
//    loadDownloadList();
//    lastSaved = System.currentTimeMillis();
//    pendingDownloads = new ArrayList<>();
//    qMgr = QueueManager.getInstance();
//    //    qMgr.fixCorruptEntries(getDownloadIds(), this);
//    QueueScheduler.getInstance().start();
//    HttpContext.getInstance().init();
//    if (Config.getInstance().isMonitorClipboard()) {
//      ClipboardMonitor.getInstance().startMonitoring();
//    }
//  }
//
//  public void exit() {
//    saveDownloadList();
//    qMgr.saveQueues();
//    Config.getInstance().save();
//    System.exit(0);
//  }
//
//  @Override
//  public void downloadFinished(long id) {
//    //    DownloadEntry ent = downloads.get(id);
//    var ent = AppContext.INSTANCE.getDb().getById(id);
//    ent.setState(DownloadEntryState.FINISHED); // XDMConstants.FINISHED);
//    var d = downloaders.remove(id);
//    if (d != null && d.getSize() < 0) {
//      ent.setSize(d.getDownloaded());
//    }
//    DownloadWindow wnd = downloadWindows.get(id);
//    if (wnd != null) {
//      wnd.close(XDMConstants.FINISHED, 0);
//      downloadWindows.remove(id);
//      //      if (ent.isStartedByUser()) {
//      //        if (Config.getInstance().showDownloadCompleteWindow()) {
//      //          new DownloadCompleteWnd(ent.getFile(), getFolder(ent)).setVisible(true);
//      //        }
//      //      }
//    }
//    notifyListeners(null);
//    saveDownloadList();
//    //    if (Config.getInstance().isExecAntivir()) {
//    //      if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getAntivirExe())) {
//    //        execAntivir();
//    //      }
//    //    }
//    //
//    //    processNextItem(id);
//    //    if (isAllFinished()) {
//    //      if (Config.getInstance().isAutoShutdown()) {
//    //        initShutdown();
//    //      }
//    //      if (Config.getInstance().isExecCmd()) {
//    //        execCmd();
//    //      }
//    //    }
//  }
//
//  @Override
//  public void downloadFailed(long id, ErrorCode errorCode) {
//    var d = downloaders.remove(id);
//    //    if (id == null) {
//    //      Logger.log("Download failed, id null");
//    //      return;
//    //    }
//    DownloadWindow wnd = downloadWindows.get(id);
//    if (wnd != null) {
//      wnd.close(XDMConstants.FAILED, errorCode.ordinal());
//      downloadWindows.remove(id);
//    } else {
//      Logger.log("Wnd is null!!!");
//    }
//    var ent = AppContext.INSTANCE.getDb().getById(id);
//    ent.setState(DownloadEntryState.PAUSED);
//    notifyListeners(id);
//    saveDownloadList();
//    Logger.log("removed");
//    // processNextItem(id);
//  }
//
//  @Override
//  public void downloadStopped(long id) {
//    downloaders.remove(id);
//    DownloadWindow wnd = downloadWindows.get(id);
//    if (wnd != null) {
//      wnd.close(XDMConstants.PAUSED, 0);
//      downloadWindows.remove(id);
//    }
//    var ent = AppContext.INSTANCE.getDb().getById(id);
//    ent.setState(DownloadEntryState.PAUSED);
//    notifyListeners(id);
//    saveDownloadList();
//    // processNextItem(id);
//  }
//
//  @Override
//  public void downloadConfirmed(long id) {
//    Logger.log("confirmed " + id);
//    var d = downloaders.get(id);
//    var ent = AppContext.INSTANCE.getDb().getById(id);
//    ent.setSize(d.getSize());
//
//    // if (isSameFile(ent.getFolder(),
//    // Config.getInstance().getDownloadFolder())) {
//    // if (ent.getCategory() != XDMConstants.OTHER) {
//    // File folderNew = new File(Config.getInstance().getDownloadFolder(),
//    // XDMUtils.getFolderForCategory(ent.getCategory()));
//    // if (!folderNew.exists()) {
//    // folderNew.mkdirs();
//    // }
//    // ent.setFolder(folderNew.getAbsolutePath());
//    // }
//    // }
//
//    DownloadWindow wnd = downloadWindows.get(id);
//    if (wnd != null) {
//      wnd.update(d, ent.getFileName());
//    }
//    notifyListeners(id);
//    saveDownloadList();
//  }
//
//  @Override
//  public void downloadUpdated(long id) {
//    try {
//      var ent = AppContext.INSTANCE.getDb().getById(id);
//      var d = downloaders.get(id);
//      if (d == null) {
//        Logger.log("################# sync error ##############");
//        return;
//      }
//      ent.setSize(d.getSize());
//      ent.setDownloaded(d.getDownloaded());
//      ent.setProgress(d.getProgress().get());
//      ent.setState(
//          d.isAssembling() ? DownloadEntryState.ASSEMBLING : DownloadEntryState.DOWNLOADING);
//      DownloadWindow wnd = downloadWindows.get(id);
//      if (wnd != null) {
//        wnd.update(d, ent.getFileName());
//      }
//    } finally {
//      notifyListeners(id);
//      long now = System.currentTimeMillis();
//      if (now - lastSaved > 5000) {
//        saveDownloadList();
//        lastSaved = now;
//      }
//    }
//  }
//
//  public static synchronized XDMApp getInstance() {
//    if (_this == null) {
//      _this = new XDMApp();
//    }
//    return _this;
//  }
//
//  //  public void addLinks(final List<HttpMetadata> list) {
//  //    SwingUtilities.invokeLater(
//  //        new Runnable() {
//  //          @Override
//  //          public void run() {
//  //            BatchDownloadWnd wnd = new BatchDownloadWnd(list);
//  //            wnd.setVisible(true);
//  //          }
//  //        });
//  //  }
//
//  //  private boolean checkForRefreshLink(final HttpMetadata metadata) {
//  //    return refreshCallback != null && refreshCallback.isValidLink(metadata);
//  //  }
//
//  public void addNewDownload(final Metadata metadata, final String file) {
//    //    if (checkForRefreshLink(metadata)) {
//    //      return;
//    //    }
//    SwingUtilities.invokeLater(
//        new Runnable() {
//          @Override
//          public void run() {
//            String fileName;
//            String folderPath;
//
//            if (StringUtils.isNullOrEmptyOrBlank(file)) {
//              if (metadata != null) {
//                fileName = XDMUtils.getFileName(metadata.getPrimaryUrl());
//              } else {
//                fileName = null;
//              }
//              folderPath = null;
//            } else {
//              var path = Paths.get(file);
//
//              fileName = path.getFileName().toString();
//
//              var parentPath = path.getParent();
//              if (parentPath != null && parentPath.isAbsolute()) {
//                folderPath = parentPath.toString();
//              } else {
//                String downloadFolderPath;
//                if (Config.getInstance().isForceSingleFolder()) {
//                  downloadFolderPath = Config.getInstance().getDownloadFolder();
//                } else {
//                  var category = XDMUtils.findCategory(file);
//                  downloadFolderPath = XDMApp.getInstance().getFolder(category);
//                }
//
//                if (parentPath != null) {
//                  folderPath = Paths.get(downloadFolderPath, parentPath.toString()).toString();
//                } else {
//                  folderPath = downloadFolderPath;
//                }
//              }
//            }
//
//            if (metadata != null
//                && (Config.getInstance().isQuietMode()
//                    || Config.getInstance().isDownloadAutoStart())) {
//              // createDownload(fileName, folderPath, metadata, true, "", 0, 0);
//              // return;
//            }
//
//            new NewDownloadWindow(metadata, fileName, folderPath).setVisible(true);
//          }
//        });
//  }
//
//  public void addDownload(final HttpSource metadata, final String file) {
//    // if (refreshCallback != null) {
//    //      if (refreshCallback.isValidLink(metadata)) {
//    //        return;
//    //      }
//    // HttpMetadata md = HttpMetadata.load(refreshCallback.getId());
//    // if (md.getType() == metadata.getType()) {
//    // if (md instanceof DashMetadata) {
//    // DashMetadata dm1 = (DashMetadata) md;
//    // DashMetadata dm2 = (DashMetadata) metadata;
//    // if (dm1.getLen1() == dm2.getLen1() && dm1.getLen2() ==
//    // dm2.getLen2()) {
//    // if(refreshCallback.isValidLink(metadata))
//    // dm1.setUrl(dm2.getUrl());
//    // dm1.setUrl2(dm2.getUrl2());
//    // dm1.setHeaders(dm2.getHeaders());
//    // dm1.setLen1(dm2.getLen1());
//    // dm1.setLen2(dm2.getLen2());
//    // dm1.save();
//    // }
//    // } else if (md instanceof HlsMetadata) {
//    // HlsMetadata hm1 = (HlsMetadata) md;
//    // HlsMetadata hm2 = (HlsMetadata) metadata;
//    // hm1
//    // }
//    // }
//    // }
//    SwingUtilities.invokeLater(
//        new Runnable() {
//          @Override
//          public void run() {
//            String fileName;
//            String folderPath;
//
//            if (StringUtils.isNullOrEmptyOrBlank(file)) {
//              if (metadata != null) {
//                fileName = XDMUtils.getFileName(metadata.getUrl());
//              } else {
//                fileName = null;
//              }
//              folderPath = null;
//            } else {
//              var path = Paths.get(file);
//
//              fileName = path.getFileName().toString();
//
//              var parentPath = path.getParent();
//              if (parentPath != null && parentPath.isAbsolute()) {
//                folderPath = parentPath.toString();
//              } else {
//                String downloadFolderPath;
//                if (Config.getInstance().isForceSingleFolder()) {
//                  downloadFolderPath = Config.getInstance().getDownloadFolder();
//                } else {
//                  var category = XDMUtils.findCategory(file);
//                  downloadFolderPath = XDMApp.getInstance().getFolder(category);
//                }
//
//                if (parentPath != null) {
//                  folderPath = Paths.get(downloadFolderPath, parentPath.toString()).toString();
//                } else {
//                  folderPath = downloadFolderPath;
//                }
//              }
//            }
//
//            if (metadata != null
//                && (Config.getInstance().isQuietMode()
//                    || Config.getInstance().isDownloadAutoStart())) {
//              createDownload(fileName, folderPath, metadata, true, "", 0, 0);
//              return;
//            }
//
//            new NewDownloadWindow(metadata, fileName, folderPath).setVisible(true);
//          }
//        });
//  }
//
//  //  public void addVideo(final HttpMetadata metadata, final String file) {
//  //    if (refreshCallback != null) {
//  //      if (refreshCallback.isValidLink(metadata)) {
//  //        return;
//  //      }
//  //    }
//  //    SwingUtilities.invokeLater(
//  //        new Runnable() {
//  //          @Override
//  //          public void run() {
//  //            //            if (!XDMUtils.isFFmpegInstalled()) {
//  //            //              if (JOptionPane.showConfirmDialog(
//  //            //                      null,
//  //            //                      StringResource.get("MSG_INSTALL_ADDITIONAL_COMPONENTS"),
//  //            //                      StringResource.get("MSG_COMPONENT_TITLE"),
//  //            //                      JOptionPane.YES_NO_OPTION)
//  //            //                  == JOptionPane.YES_OPTION) {
//  //            //                FFmpegDownloader fd = new FFmpegDownloader();
//  //            //                fd.start();
//  //            //              }
//  //            //              return;
//  //            //            }
//  //            new VideoDownloadWindow(metadata, file).setVisible(true);
//  //          }
//  //        });
//  //  }
//  //
//  //  public void addMedia(final HttpMetadata metadata, final String file, final String info) {
//  //    System.out.println("video notification: " + Config.getInstance().isShowVideoNotification());
//  //    if (Config.getInstance().isShowVideoNotification()) {
//  //      SwingUtilities.invokeLater(
//  //          new Runnable() {
//  //            @Override
//  //            public void run() {
//  //              VideoPopup.getInstance().addVideo(metadata, file, info);
//  //              BrowserMonitor.getInstance().updateSettingsAndStatus();
//  //            }
//  //          });
//  //    }
//  //  }
//  //
//  //  public void youtubeVideoTitleUpdated(String url, String title) {
//  //    if (Config.getInstance().isShowVideoNotification()) {
//  //      SwingUtilities.invokeLater(
//  //          new Runnable() {
//  //            @Override
//  //            public void run() {
//  //              if (VideoPopup.hasInstance()) {
//  //                VideoPopup.getInstance().updateYoutubeTitle(url, title);
//  //                BrowserMonitor.getInstance().updateSettingsAndStatus();
//  //              }
//  //            }
//  //          });
//  //    }
//  //  }
//
//  public void createDownload(
//      String file,
//      String folder,
//      HttpSource metadata,
//      boolean now,
//      String queueId,
//      int formatIndex,
//      int streamIndex) {
//    // metadata.save();
//    var ent =
//        xdm.app.models.DownloadEntry.builder()
//            .id(metadata.getId())
//            .state(DownloadEntryState.READY)
//            .fileName(file)
//            .dateEpoch(System.currentTimeMillis())
//            .build();
//    //    ent.setId(metadata.getId());
//    //    ent.setOutputFormatIndex(formatIndex);
//    //    ent.setState(XDMConstants.PAUSED);
//    //    ent.setFile(file);
//    //    ent.setFolder(folder);
//    //    ent.setTempFolder(Config.getInstance().getTemporaryFolder());
//    //    ent.setCategory(XDMUtils.findCategory(file));
//    //    ent.setDate(System.currentTimeMillis());
//    // putInQueue(queueId, ent);
//    // ent.setStartedByUser(now);
//    // downloads.put(metadata.getId(), ent);
//    AppContext.INSTANCE.getDb().add(ent);
//    saveDownloadList();
//    if (!now) {
//      DownloadQueue q = qMgr.getQueueById(queueId);
//      if (q != null && q.isRunning()) {
//        Logger.log("Queue is running, if no pending download pickup next available download");
//        q.next();
//      }
//    }
//    if (now) {
//      startDownload(metadata.getId(), metadata, ent, streamIndex);
//    }
//    notifyListeners(null);
//  }
//
//  // could be new or resume
//  private void startDownload(
//          long id, HttpSource metadata, xdm.app.models.DownloadEntry ent, int streams) {
//    // if (!checkAndBufferRequests(id)) {
//    //      Logger.log(
//    //          "starting "
//    //              + id
//    //              + " with: "
//    //              + metadata
//    //              + " is dash: "
//    //              + (metadata instanceof DashMetadata));
//    AbstractDownloader d = null;
//
//    //      if (metadata instanceof DashMetadata) {
//    //        Logger.log("Dash download with stream: " + streams);
//    //        if (streams == 1) {
//    //          DashMetadata dm = (DashMetadata) metadata;
//    //          dm.setUrl(dm.getUrl2()); // set video url as main url
//    //          dm.setUrl2(null);
//    //        } else if (streams == 2) {
//    //          DashMetadata dm = (DashMetadata) metadata;
//    //          dm.setUrl2(null);
//    //        } else {
//    //          Logger.log("Dash download created");
//    //          // create dash downloader
//    //          DashMetadata dm = (DashMetadata) metadata;
//    //          d = new DashDownloader(id, ent.getTempFolder(), dm);
//    //        }
//    //      }
//    //      if (metadata instanceof HlsMetadata) {
//    //        Logger.log("Hls download created");
//    //        d = new HlsDownloader(id, ent.getTempFolder(), (HlsMetadata) metadata);
//    //      }
//    //      if (metadata instanceof HdsMetadata) {
//    //        Logger.log("Hds download created");
//    //        d = new HdsDownloader(id, ent.getTempFolder(), (HdsMetadata) metadata);
//    //      }
//    //      if (d == null) {
//    //        if (metadata.getType() == XDMConstants.FTP) {
//    //          d = new FtpDownloader(id, ent.getTempFolder(), metadata);
//    //        } else {
//    //          d = new HttpDownloader(id, ent.getTempFolder(), metadata);
//    //        }
//    //      }
//
//    d = new HttpDownloader(id, Config.getInstance().getTemporaryFolder(), metadata, this, null);
//
//    // d.setOuputMediaFormat(ent.getOutputFormatIndex());
//    downloaders.put(id, d);
//    // d.registerListener(this);
//    ent.setState(DownloadEntryState.DOWNLOADING);
//    d.start();
//
//    if (!Config.getInstance().isQuietMode() && Config.getInstance().showDownloadWindow()) {
//      DownloadWindow wnd = new DownloadWindow(id, this);
//      downloadWindows.put(id, wnd);
//      wnd.setVisible(true);
//    }
//    //    } else {
//    //      Logger.log(id + ": Maximum download limit reached, queueing request");
//    //    }
//  }
//
//  public void pauseDownload(long id) {
//    var d = downloaders.get(id);
//    if (d != null) {
//      d.stop();
//      // d.unregisterListener();
//    }
//  }
//
//  public void resumeDownload(long id, boolean startedByUser) {
//    //    DownloadEntry ent = downloads.get(id);
//    //    ent.setStartedByUser(startedByUser);
//    //    if (ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED) {
//    //      if (!checkAndBufferRequests(id)) {
//    //        ent.setState(XDMConstants.DOWNLOADING);
//    //        HttpMetadata metadata = HttpMetadata.load(id);
//    //        if (!Config.getInstance().isQuietMode()
//    //            && Config.getInstance().showDownloadWindow()
//    //            && ent.isStartedByUser()) {
//    //          DownloadWindow wnd = new DownloadWindow(id, this);
//    //          downloadWindows.put(id, wnd);
//    //          wnd.setVisible(true);
//    //        }
//    //        Downloader d = null;
//    //        if (metadata instanceof DashMetadata) {
//    //          DashMetadata dm = (DashMetadata) metadata;
//    //          Logger.log("Dash download- url1: " + dm.getUrl() + " url2: " + dm.getUrl2());
//    //          d = new DashDownloader(id, ent.getTempFolder(), dm);
//    //        }
//    //        if (metadata instanceof HlsMetadata) {
//    //          HlsMetadata hm = (HlsMetadata) metadata;
//    //          Logger.log("HLS download- url1: " + hm.getUrl());
//    //          d = new HlsDownloader(id, ent.getTempFolder(), hm);
//    //        }
//    //        if (metadata instanceof HdsMetadata) {
//    //          HdsMetadata hm = (HdsMetadata) metadata;
//    //          Logger.log("HLS download- url1: " + hm.getUrl());
//    //          d = new HdsDownloader(id, ent.getTempFolder(), hm);
//    //        }
//    //        if (d == null) {
//    //          Logger.log("normal download");
//    //          if (metadata.getType() == XDMConstants.FTP) {
//    //            d = new FtpDownloader(id, ent.getTempFolder(), metadata);
//    //          } else {
//    //            d = new HttpDownloader(id, ent.getTempFolder(), metadata);
//    //          }
//    //        }
//    //        downloaders.put(id, d);
//    //        d.setOuputMediaFormat(ent.getOutputFormatIndex());
//    //        d.registerListener(this);
//    //        d.resume();
//    //
//    //      } else {
//    //        Logger.log(id + ": Maximum download limit reached, queueing request");
//    //      }
//    //      notifyListeners(null);
//    //    }
//  }
//
//  //  public void restartDownload(long id) {
//  //    DownloadEntry ent = downloads.get(id);
//  //    if (ent.getState() == XDMConstants.PAUSED
//  //        || ent.getState() == XDMConstants.FAILED
//  //        || ent.getState() == XDMConstants.FINISHED) {
//  //      ent.setState(XDMConstants.PAUSED);
//  //      clearData(ent);
//  //      resumeDownload(id, true);
//  //    } else {
//  //      return;
//  //    }
//  //  }
//
//  public synchronized void addListener(ListChangeListener listener) {
//    listChangeListeners.add(listener);
//  }
//
//  public synchronized void removeListener(ListChangeListener listener) {
//    listChangeListeners.remove(listener);
//  }
//
//  private void notifyListeners(Long id) {
//    if (listChangeListeners != null) {
//      for (int i = 0; i < listChangeListeners.size(); i++)
//        if (id != null) listChangeListeners.get(i).listItemUpdated(id);
//        else listChangeListeners.get(i).listChanged();
//    }
//  }
//
//  //  public DownloadEntry getEntry(long id) {
//  //    return downloads.get(id);
//  //  }
//
//  //  public ArrayList<Long> getDownloadList(
//  //      int category, int state, String searchText, String queueId) {
//  //    ArrayList<Long> idList = new ArrayList<>();
//  //    Iterator<Long> keyIterator = downloads.keySet().iterator();
//  //    while (keyIterator.hasNext()) {
//  //      var key = keyIterator.next();
//  //      DownloadEntry ent = downloads.get(key);
//  //      if (state == XDMConstants.ALL
//  //          || state
//  //              == (ent.getState() == XDMConstants.FINISHED
//  //                  ? XDMConstants.FINISHED
//  //                  : XDMConstants.UNFINISHED)) {
//  //        if (category == XDMConstants.ALL || category == ent.getCategory()) {
//  //          boolean matched = false;
//  //          if (!"ALL".equals(queueId)) {
//  //            if (queueId != null) {
//  //              if (!queueId.equals(ent.getQueueId())) {
//  //                continue;
//  //              }
//  //            }
//  //          }
//  //
//  //          if (searchText != null && searchText.length() > 0) {
//  //            if (ent.getFile().contains(searchText)) {
//  //              matched = true;
//  //            }
//  //          } else {
//  //            matched = true;
//  //          }
//  //          if (matched) {
//  //            idList.add(ent.getId());
//  //          }
//  //        }
//  //      }
//  //    }
//  //    return idList;
//  //  }
//
//  //  private void clearData(DownloadEntry ent) {
//  //    if (ent == null) return;
//  //    File folder = new File(ent.getTempFolder(), String.valueOf(ent.getId()));
//  //    File[] files = folder.listFiles();
//  //    if (files != null) {
//  //      for (int i = 0; i < files.length; i++) {
//  //        files[i].delete();
//  //      }
//  //    }
//  //    folder.delete();
//  //  }
//
//  @Override
//  public String getOutputFolder(long id) {
//    return Config.getInstance().getDownloadFolder();
//    //    DownloadEntry ent = downloads.get(id);
//    //    if (ent == null) {
//    //      return Config.getInstance().getCategoryOther();
//    //    } else {
//    //      return getFolder(ent);
//    //    }
//  }
//
//  @Override
//  public String getOutputFileName(long id) {
//    var ent = AppContext.INSTANCE.getDb().getById(id);
//    //    if (update) {
//    //      updateFileName(ent);
//    //    }
//    return ent.getFileName();
//  }
//
//  //  public String getFolder(DownloadEntry ent) {
//  //    if (ent.getFolder() != null) {
//  //      return ent.getFolder();
//  //    }
//  //    if (Config.getInstance().isForceSingleFolder()) {
//  //      return Config.getInstance().getDownloadFolder();
//  //    }
//  //    int category = ent.getCategory();
//  //    return this.getFolder(category);
//  //  }
//
//  public String getFolder(int category) {
//    switch (category) {
//      case XDMConstants.DOCUMENTS:
//        return Config.getInstance().getCategoryDocuments();
//      case XDMConstants.MUSIC:
//        return Config.getInstance().getCategoryMusic();
//      case XDMConstants.VIDEO:
//        return Config.getInstance().getCategoryVideos();
//      case XDMConstants.PROGRAMS:
//        return Config.getInstance().getCategoryPrograms();
//      case XDMConstants.COMPRESSED:
//        return Config.getInstance().getCategoryCompressed();
//      default:
//        return Config.getInstance().getCategoryOther();
//    }
//  }
//
//  private void loadDownloadList() {
//    AppContext.INSTANCE.getDb().load();
//    //    File file = new File(Config.getInstance().getDataFolder(), "downloads.txt");
//    //    loadDownloadList(file);
//  }
//
//  //  public void loadDownloadList(File file) {
//  //    if (!file.exists()) {
//  //      return;
//  //    }
//  //    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//  //    // BufferedReader reader = null;
//  //    try (BufferedReader reader =
//  //        new BufferedReader(
//  //            new InputStreamReader(new FileInputStream(file), Charset.forName("UTF-8")))) {
//  //      // reader = new BufferedReader(new InputStreamReader(new
//  //      // FileInputStream(file),
//  //      // Charset.forName("UTF-8")));
//  //      String line = reader.readLine();
//  //      if (line == null) {
//  //        throw new NullPointerException("Unexpected EOF");
//  //      }
//  //      int count = Integer.parseInt(line.trim());
//  //      for (int i = 0; i < count; i++) {
//  //        int fieldCount = Integer.parseInt(XDMUtils.readLineSafe(reader).trim());
//  //        DownloadEntry ent = new DownloadEntry();
//  //        for (int j = 0; j < fieldCount; j++) {
//  //          String ln = reader.readLine();
//  //          if (ln == null) {
//  //            return;
//  //          }
//  //          int index = ln.indexOf(":");
//  //          if (index > 0) {
//  //            String key = ln.substring(0, index).trim();
//  //            String val = ln.substring(index + 1).trim();
//  //            if (key.equals("id")) {
//  //              ent.setId(Long.parseLong(val));
//  //            }
//  //            if (key.equals("file")) {
//  //              ent.setFile(val);
//  //            }
//  //            if (key.equals("category")) {
//  //              ent.setCategory(Integer.parseInt(val));
//  //            }
//  //            if (key.equals("state")) {
//  //              int state = Integer.parseInt(val);
//  //              ent.setState(state == XDMConstants.FINISHED ? state : XDMConstants.PAUSED);
//  //            }
//  //            if (key.equals("folder")) {
//  //              ent.setFolder(val);
//  //            }
//  //            if (key.equals("date")) {
//  //              ent.setDate(dateFormat.parse(val).getTime());
//  //            }
//  //            if (key.equals("downloaded")) {
//  //              ent.setDownloaded(Long.parseLong(val));
//  //            }
//  //            if (key.equals("size")) {
//  //              ent.setSize(Long.parseLong(val));
//  //            }
//  //            if (key.equals("progress")) {
//  //              ent.setProgress(Integer.parseInt(val));
//  //            }
//  //            if (key.equals("queueid")) {
//  //              ent.setQueueId(val);
//  //            }
//  //            if (key.equals("formatIndex")) {
//  //              ent.setOutputFormatIndex(Integer.parseInt(val));
//  //            }
//  //            if (key.equals("tempfolder")) {
//  //              ent.setTempFolder(val);
//  //            }
//  //          }
//  //        }
//  //        downloads.put(ent.getId(), ent);
//  //      }
//  //      reader.close();
//  //    } catch (Exception e) {
//  //      Logger.log(e);
//  //    }
//  //  }
//
//  private void saveDownloadList() {
//    //    File file = new File(Config.getInstance().getDataFolder(), "downloads.txt");
//    //    saveDownloadList(file);
//    AppContext.INSTANCE.getDb().save();
//  }
//
//  //  public void saveDownloadList(File file) {
//  //    DownloadsDB.INSTANCE.save();;
//  //    int count = downloads.size();
//  //    BufferedWriter writer = null;
//  //    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//  //    String newLine = System.getProperty("line.separator");
//  //    try {
//  //      writer =
//  //          new BufferedWriter(
//  //              new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8")));
//  //      writer.write(count + "");
//  //      writer.newLine();
//  //      Iterator<Long> keyIterator = downloads.keySet().iterator();
//  //      while (keyIterator.hasNext()) {
//  //        var key = keyIterator.next();
//  //        DownloadEntry ent = downloads.get(key);
//  //        int c = 0;
//  //        StringBuffer sb = new StringBuffer();
//  //        sb.append("id: " + ent.getId() + newLine);
//  //        c++;
//  //        sb.append("file: " + ent.getFile() + newLine);
//  //        c++;
//  //        sb.append("category: " + ent.getCategory() + newLine);
//  //        c++;
//  //        sb.append("state: " + ent.getState() + newLine);
//  //        c++;
//  //        if (ent.getFolder() != null) {
//  //          sb.append("folder: " + ent.getFolder() + newLine);
//  //          c++;
//  //        }
//  //        sb.append("date: " + dateFormat.format(new Date(ent.getDate())) + newLine);
//  //        c++;
//  //        sb.append("downloaded: " + ent.getDownloaded() + newLine);
//  //        c++;
//  //        sb.append("size: " + ent.getSize() + newLine);
//  //        c++;
//  //        sb.append("progress: " + ent.getProgress() + newLine);
//  //        c++;
//  //        if (ent.getTempFolder() != null) {
//  //          sb.append("tempfolder: " + ent.getTempFolder() + newLine);
//  //          c++;
//  //        }
//  //        if (ent.getQueueId() != null) {
//  //          sb.append("queueid: " + ent.getQueueId() + newLine);
//  //          c++;
//  //        }
//  //        sb.append("formatIndex: " + ent.getOutputFormatIndex() + newLine);
//  //        c++;
//  //        writer.write(c + newLine);
//  //        writer.write(sb.toString());
//  //      }
//  //      writer.close();
//  //    } catch (Exception e) {
//  //      Logger.log(e);
//  //      try {
//  //        if (writer != null) writer.close();
//  //      } catch (Exception e1) {
//  //      }
//  //    }
//  //  }
//
//  public void hidePrgWnd(long id) {
//    DownloadWindow wnd = downloadWindows.get(id);
//    if (wnd != null) {
//      downloadWindows.remove(id);
//      wnd.close(XDMConstants.PAUSED, 0);
//    }
//  }
//
//  private synchronized int getActiveDownloadCount() {
//    int count = 0;
//    //    Iterator<Long> keyIterator = downloads.keySet().iterator();
//    //    while (keyIterator.hasNext()) {
//    //      var key = keyIterator.next();
//    //      DownloadEntry ent = downloads.get(key);
//    //      int state = ent.getState();
//    //      if (state == XDMConstants.FINISHED
//    //          || state == XDMConstants.PAUSED
//    //          || state == XDMConstants.FAILED) continue;
//    //      count++;
//    //    }
//    return count;
//  }
//
//  //  private synchronized boolean checkAndBufferRequests(long id) {
//  //    int actCount = getActiveDownloadCount();
//  //    if (Config.getInstance().getMaxDownloads() > 0
//  //        && actCount >= Config.getInstance().getMaxDownloads()) {
//  //      Logger.log("active: " + actCount + " max: " + Config.getInstance().getMaxDownloads());
//  //      if (!pendingDownloads.contains(id)) {
//  //        pendingDownloads.add(id);
//  //      }
//  //      return true;
//  //    }
//  //    return false;
//  //  }
//
//  //  private synchronized void processNextItem(Long lastId) {
//  //    processPendingRequests();
//  //    if (lastId == null) return;
//  //    DownloadEntry ent = getEntry(lastId);
//  //    if (ent == null) {
//  //      return;
//  //    }
//  //    DownloadQueue queue = null;
//  //    if ("".equals(ent.getQueueId())) {
//  //      queue = qMgr.getDefaultQueue();
//  //    } else {
//  //      queue = qMgr.getQueueById(ent.getQueueId());
//  //    }
//  //    if (queue != null && queue.isRunning()) {
//  //      queue.next();
//  //    }
//  //  }
//
//  //  private void processPendingRequests() {
//  //    int activeCount = getActiveDownloadCount();
//  //    int maxDownloadCount = Config.getInstance().getMaxDownloads();
//  //    List<Long> tobeStartedIds = new ArrayList<>();
//  //    if (maxDownloadCount - activeCount > 0) {
//  //      for (int i = 0; i < Math.min(maxDownloadCount, pendingDownloads.size()); i++) {
//  //        var ent = pendingDownloads.get(i);
//  //        tobeStartedIds.add(ent);
//  //      }
//  //    }
//  //    if (tobeStartedIds.size() > 0) {
//  //      for (int i = 0; i < tobeStartedIds.size(); i++) {
//  //        long id = tobeStartedIds.get(i);
//  //        pendingDownloads.remove(id);
//  //        DownloadEntry ent = getEntry(id);
//  //        if (ent != null) {
//  //          resumeDownload(id, ent.isStartedByUser());
//  //        }
//  //      }
//  //    }
//  //  }
//
//  public boolean queueItemPending(String queueId) {
//    //    if (queueId == null) return false;
//    //    for (int i = 0; i < pendingDownloads.size(); i++) {
//    //      long id = pendingDownloads.get(i);
//    //      DownloadEntry ent = getEntry(id);
//    //      if (ent == null || ent.getQueueId() == null) continue;
//    //      if (ent.getQueueId().equals(queueId)) {
//    //        return true;
//    //      }
//    //    }
//    return false;
//  }
//
//  public ArrayList<DownloadQueue> getQueueList() {
//    return qMgr.getQueueList();
//  }
//
//  public DownloadQueue getQueueById(String queueId) {
//    return qMgr.getQueueById(queueId);
//  }
//
//  //  private void putInQueue(String queueId, DownloadEntry ent) {
//  //    DownloadQueue q = getQueueById(queueId);
//  //    long id = ent.getId();
//  //    if (q == null) {
//  //      Logger.log("No queue found for: '" + queueId + "'");
//  //      return;
//  //    }
//  //    String qid = ent.getQueueId();
//  //    DownloadQueue oldQ = getQueueById(qid);
//  //    Logger.log("Adding to: '" + queueId + "'");
//  //    if (!q.getQueueId().equals(qid)) {
//  //      if (oldQ != null) {
//  //        // remove from previous queue
//  //        oldQ.removeFromQueue(id);
//  //      }
//  //      ent.setQueueId(queueId);
//  //      q.addToQueue(id);
//  //    }
//  //  }
//
//  //  @Override
//  //  public int compare(Long key1, Long key2) {
//  //    xdm.app.models.DownloadEntry ent1 = DownloadsDB.INSTANCE.getById(key1);
//  //    xdm.app.models.DownloadEntry ent2 = DownloadsDB.INSTANCE.getById(key2);
//  //    if (ent1 == null) return -1;
//  //    if (ent2 == null) return 1;
//  //    if (ent1.getDate() > ent2.getDate()) {
//  //      return 1;
//  //    } else if (ent1.getDate() < ent2.getDate()) {
//  //      return -1;
//  //    } else {
//  //      return 0;
//  //    }
//  //  }
//
//  //  private Iterator<Long> getDownloadIds() {
//  //    return downloads.keySet().iterator();
//  //  }
//
//  public boolean isAllFinished() {
//    if (getActiveDownloadCount() != 0) {
//      return false;
//    }
//    if (pendingDownloads.size() != 0) {
//      return false;
//    }
//    for (int i = 0; i < QueueManager.getInstance().getQueueList().size(); i++) {
//      DownloadQueue q = QueueManager.getInstance().getQueueList().get(i);
//      if (q.hasPendingItems()) {
//        return false;
//      }
//    }
//    return true;
//  }
//
//  private void initShutdown() {
//    Logger.log("Initiating shutdown");
//    int os = XDMUtils.detectOS();
//    if (os == XDMUtils.LINUX) {
//      LinuxUtils.initShutdown();
//    } else if (os == XDMUtils.WINDOWS) {
//      WinUtils.initShutdown();
//    } else if (os == XDMUtils.MAC) {
//      MacUtils.initShutdown();
//    }
//  }
//
//  //  public int deleteDownloads(List<Long> ids, boolean outflie) {
//  //    int c = 0;
//  //    for (int i = 0; i < ids.size(); i++) {
//  //      long id = ids.get(i);
//  //      var ent = DownloadsDB.INSTANCE.getById(id);
//  //      if (ent != null) {
//  //        if (ent.getState() == XDMConstants.FINISHED
//  //            || ent.getState() == XDMConstants.PAUSED
//  //            || ent.getState() == XDMConstants.FAILED) {
//  //          this.downloads.remove(id);
//  //          if (pendingDownloads.contains(id)) {
//  //            pendingDownloads.remove(id);
//  //          }
//  //          String qId = ent.getQueueId();
//  //          if (qId != null) {
//  //            DownloadQueue q = getQueueById(qId);
//  //            if (q != null) {
//  //              if (q.getQueueId().length() > 0) {
//  //                q.removeFromQueue(id);
//  //              }
//  //            }
//  //          }
//  //          deleteFiles(ent, outflie);
//  //          c++;
//  //        }
//  //      }
//  //    }
//  //    saveDownloadList();
//  //    notifyListeners(null);
//  //    return ids.size() - c;
//  //  }
//
//  //  private void deleteFiles(DownloadEntry ent, boolean outfile) {
//  //    // DownloadEntry ent = getEntry(id);
//  //    if (ent == null) return;
//  //    long id = ent.getId();
//  //    Logger.log("Deleting metadata for " + id);
//  //    File mf = new File(Config.getInstance().getMetadataFolder(), String.valueOf(id));
//  //    boolean deleted = mf.delete();
//  //    Logger.log("Deleted manifest " + id + " " + deleted);
//  //    File df = new File(ent.getTempFolder(), String.valueOf(id));
//  //    File[] files = df.listFiles();
//  //    if (files != null && files.length > 0) {
//  //      for (File f : files) {
//  //        deleted = f.delete();
//  //        Logger.log("Deleted tmp file " + id + " " + deleted);
//  //      }
//  //    }
//  //    deleted = df.delete();
//  //    Logger.log("Deleted tmp folder " + id + " " + deleted);
//  //    if (outfile) {
//  //      File f = new File(XDMApp.getInstance().getFolder(ent), ent.getFile());
//  //      f.delete();
//  //    }
//  //  }
//
//  public void changeOutputFile(long id) {}
//
//  public String getURL(long id) {
//    throw new RuntimeException("Not implemented");
//    //    try {
//    //      HttpMetadata metadata = HttpMetadata.load(id);
//    //      if (metadata instanceof DashMetadata) {
//    //        DashMetadata dm = (DashMetadata) metadata;
//    //        return dm.getUrl() + "\n" + dm.getUrl2();
//    //      } else {
//    //        return metadata.getUrl();
//    //      }
//    //    } catch (Exception e) {
//    //      Logger.log(e);
//    //    }
//    //    return "";
//  }
//
//  //  public void registerRefreshCallback(LinkRefreshCallback callback) {
//  //    this.refreshCallback = callback;
//  //  }
//
//  public void unregisterRefreshCallback() {
//    //    this.refreshCallback = null;
//  }
//
//  //  public void deleteCompleted() {
//  //    Iterator<Long> allIds = downloads.keySet().iterator();
//  //    ArrayList<Long> idList = new ArrayList<>();
//  //    while (allIds.hasNext()) {
//  //      long id = allIds.next();
//  //      DownloadEntry ent = downloads.get(id);
//  //      if (ent.getState() == XDMConstants.FINISHED) {
//  //        idList.add(id);
//  //      }
//  //    }
//  //    deleteDownloads(idList, false);
//  //  }
//
//  //  public boolean promptCredential(long id, String msg, boolean proxy) {
//  //    DownloadEntry ent = getEntry(id);
//  //    if (ent == null) return false;
//  //    if (!ent.isStartedByUser()) return false;
//  //    PasswordAuthentication pauth = getCredential(msg, proxy);
//  //    if (pauth == null) {
//  //      return false;
//  //    }
//  //    if (proxy) {
//  //      Config.getInstance().setProxyUser(pauth.getUserName());
//  //      if (pauth.getPassword() != null) {
//  //        Config.getInstance().setProxyPass(new String(pauth.getPassword()));
//  //      }
//  //    } else {
//  //      Logger.log("saving password for: " + msg);
//  //      CredentialManager.getInstance().addCredentialForHost(msg, pauth);
//  //    }
//  //    return true;
//  //  }
//
//  private PasswordAuthentication getCredential(String msg, boolean proxy) {
//    JTextField user = new JTextField(30);
//    JPasswordField pass = new JPasswordField(30);
//
//    String prompt =
//        proxy
//            ? StringResource.get("PROMPT_PROXY")
//            : String.format(StringResource.get("PROMPT_SERVER"), msg);
//
//    Object[] obj = new Object[5];
//    obj[0] = prompt;
//    obj[1] = StringResource.get("DESC_USER");
//    obj[2] = user;
//    obj[3] = StringResource.get("DESC_PASS");
//    obj[4] = pass;
//
//    if (JOptionPane.showOptionDialog(
//            null,
//            obj,
//            StringResource.get("PROMPT_CRED"),
//            JOptionPane.OK_CANCEL_OPTION,
//            JOptionPane.PLAIN_MESSAGE,
//            null,
//            null,
//            null)
//        == JOptionPane.OK_OPTION) {
//      PasswordAuthentication pauth = new PasswordAuthentication(user.getText(), pass.getPassword());
//      return pauth;
//    }
//    return null;
//  }
//
//  private void execCmd() {
//    if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getCustomCmd())) {
//      XDMUtils.exec(Config.getInstance().getCustomCmd());
//    }
//  }
//
//  private void execAntivir() {
//    XDMUtils.exec(
//        Config.getInstance().getAntivirExe()
//            + " "
//            + (Config.getInstance().getAntivirCmd() == null
//                ? ""
//                : Config.getInstance().getAntivirCmd()));
//  }
//
//  private void updateFileName(xdm.app.models.DownloadEntry ent) {
//    if (Config.getInstance().getDuplicateAction() == XDMConstants.DUP_ACT_OVERWRITE) {
//      return;
//    }
//    Logger.log("checking for same named file on disk...");
//    var id = ent.getId();
//    String outputFolder = getOutputFolder(id);
//    File f = new File(outputFolder, ent.getFileName());
//    int c = 1;
//    String ext = XDMUtils.getExtension(f.getAbsolutePath());
//    if (ext == null) {
//      ext = "";
//    }
//    String f2 = XDMUtils.getFileNameWithoutExtension(ent.getFileName());
//    while (f.exists()) {
//      // int index = f2.lastIndexOf("_");
//      // if (index > 0) {
//      // f2 = f2.substring(0, index);
//      // }
//      f = new File(outputFolder, f2 + "_" + c + ext);
//      c++;
//    }
//    Logger.log("Updating file name- old: " + ent.getFileName() + " new: " + f.getName());
//    ent.setFileName(f.getName());
//  }
//
//  //  public void importList(File file) {
//  //    loadDownloadList(file);
//  //  }
//  //
//  //  public void exportList(File file) {
//  //    saveDownloadList(file);
//  //  }
//
//  public void notifyComponentUpdate() {
//    //		pendingNotification = UpdateChecker.COMP_UPDATE_AVAILABLE;
//    //		if (mainWindow != null) {
//    //			mainWindow.showNotification();
//    //		}
//
//    // if (mainWindow == null) {
//    // pendingNotification = UpdateChecker.COMP_UPDATE_AVAILABLE;
//    // } else {
//    // mainWindow.showUpdateNotification(UpdateChecker.COMP_UPDATE_AVAILABLE);
//    // }
//  }
//
//  public void notifyComponentInstall() {
//    //		pendingNotification = UpdateChecker.COMP_NOT_INSTALLED;
//    //		if (mainWindow != null) {
//    //			mainWindow.showNotification();
//    //		}
//
//    // if (mainWindow == null) {
//    // pendingNotification = UpdateChecker.COMP_NOT_INSTALLED;
//    // } else {
//    // mainWindow.showUpdateNotification(UpdateChecker.COMP_NOT_INSTALLED);
//    // }
//  }
//
//  public void notifyAppUpdate() {
//    //		pendingNotification = UpdateChecker.APP_UPDATE_AVAILABLE;
//    //		if (mainWindow != null) {
//    //			mainWindow.showNotification();
//    //		}
//
//    // if (mainWindow == null) {
//    // pendingNotification = UpdateChecker.APP_UPDATE_AVAILABLE;
//    // } else {
//    // mainWindow.showUpdateNotification(UpdateChecker.APP_UPDATE_AVAILABLE);
//    // }
//  }
//
//  public void clearNotifications() {
//    //		pendingNotification = -1;
//    //		if (mainWindow != null) {
//    //			mainWindow.showNotification();
//    //		}
//
//    // if (mainWindow == null) {
//    // pendingNotification = -1;
//    // } else {
//    // mainWindow.clearNotification();
//    // }
//  }
//
//  public int getNotification() {
//    return pendingNotification;
//  }
//
//  //  private void openTempFolder(long id) {
//  //    DownloadEntry ent = getEntry(id);
//  //    if (ent == null) return;
//  //    File df = new File(ent.getTempFolder(), String.valueOf(id));
//  //    try {
//  //      XDMUtils.openFolder(null, df.getAbsolutePath());
//  //    } catch (Exception e) {
//  //      Logger.log(e);
//  //    }
//  //  }
//
//  //  public void openPreview(long id) {
//  //    DownloadEntry ent = XDMApp.getInstance().getEntry(id);
//  //    if (ent != null
//  //        && (ent.getCategory() == XDMConstants.VIDEO || ent.getCategory() == XDMConstants.MUSIC))
//  // {
//  //      if (XDMUtils.isFFmpegInstalled()) {
//  //        XDMApp.getInstance().openPreviewPlayer(id);
//  //      } else {
//  //        JOptionPane.showMessageDialog(null, StringResource.get("LBL_COMPONENT_MISSING"));
//  //      }
//  //    } else if (JOptionPane.showConfirmDialog(
//  //            null, StringResource.get("LBL_NOT_A_VIDEO"), "Preview", JOptionPane.YES_NO_OPTION)
//  //        == JOptionPane.YES_OPTION) {
//  //      XDMApp.getInstance().openTempFolder(id);
//  //    }
//  //  }
//
//  private void openPreviewPlayer(long id) {
//    XDMUtils.browseURL("http://127.0.0.1:9614/preview/media/" + id);
//  }
//
//  public void showPrgWnd(long id) {
//    //    DownloadEntry ent = getEntry(id);
//    //    if (ent == null) {
//    //      return;
//    //    }
//    //    if (ent.getState() == XDMConstants.FINISHED
//    //        || ent.getState() == XDMConstants.PAUSED
//    //        || ent.getState() == XDMConstants.FAILED) {
//    //      return;
//    //    }
//    //    DownloadWindow wnd = downloadWindows.get(id);
//    //    if (wnd == null) {
//    //      wnd = new DownloadWindow(id, this);
//    //      downloadWindows.put(id, wnd);
//    //      wnd.setVisible(true);
//    //    }
//  }
//
//  public void fileNameChanged(long id) {
//    notifyListeners(id);
//  }
//
//  //  public ArrayList<VideoPopupItem> getVideoItemsList() {
//  //    return itemList;
//  //  }
//
//  public AppWindow getMainWindow() {
//    return appWindow;
//  }
//}
