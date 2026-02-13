package xdm.app.ui.components;

import xdm.app.AppContext;
import xdm.app.constants.DownloadEntryState;
// import xdm.app.models.DownloadEntry;
import xdm.app.data.DbRecord;
import xdm.app.data.RecordStatus;
import xdm.app.ui.screens.AppWindow;
// import xdm.core.util.MetadataStore;
import xdm.core.downloaders.DownloadType;
import xdman.*;
import xdman.constants.MessageBoxResult;
// import xdman.downloaders.metadata.DashMetadata;
// import xdman.downloaders.metadata.HdsMetadata;
// import xdman.downloaders.metadata.HlsMetadata;
// import xdman.downloaders.metadata.HttpMetadata;
// import xdman.ui.components.BatchDownloadWnd;
import xdman.ui.components.BatchPatternDialog;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.List;

public class AppMenuHandler {
  public static void openFile(DbRecord ent, Window window) {
    //    if (ent != null && ent.getStatus() == RecordStatus.FINISHED) {
    //      Logger.log("Opening file for id: " + ent.getId());
    //      var md = MetadataStore.get(ent.getId());
    //      if (md != null) {
    //        try {
    //          XDMUtils.openFile(md.getFileName(), md.getFolder());
    //        } catch (FileNotFoundException e) {
    //          Logger.log(e);
    //          MessageBox.show(
    //              window,
    //              StringResource.get("ERR_MSG_FILE_NOT_FOUND"),
    //              StringResource.get("ERR_MSG_FILE_NOT_FOUND_MSG"));
    //        } catch (Exception e) {
    //          Logger.log(e);
    //        }
    //      }
    //    }
  }

  public static void openFolder(DbRecord ent, Window window) {
    if (ent.getStatus() == RecordStatus.FINISHED) {
      Logger.log("Opening folder for id: " + ent.getId());
      String folder = null;
      String fileName = null;
      if (ent.getDownloadType() == DownloadType.Http) {
        var md = AppContext.INSTANCE.getTaskInfoDB().getHttpTask(ent.getId());
        folder =
            Optional.ofNullable(md.getUserSelectedDownloadFolder())
                .orElse(md.getDefaultDownloadFolder());
        fileName = md.getFileName();
      } else if (ent.getDownloadType() == DownloadType.Hls) {
        var md = AppContext.INSTANCE.getTaskInfoDB().getHlsTask(ent.getId());
        folder =
            Optional.ofNullable(md.getUserSelectedDownloadFolder())
                .orElse(md.getDefaultDownloadFolder());
        fileName = md.getFileName();
      }
      if (folder != null) {
        try {
          Logger.log("Folder: " + folder + " File: " + fileName);
          XDMUtils.openFolder(fileName, folder);
        } catch (FileNotFoundException e) {
          Logger.log(e);
          MessageBox.show(
              window,
              StringResource.get("ERR_MSG_FILE_NOT_FOUND"),
              StringResource.get("ERR_MSG_FILE_NOT_FOUND_MSG"));
        } catch (Exception e) {
          Logger.log(e);
        }
      }
    }
  }

  public static void restartDownload(DbRecord ent) {
    //    AppContext.INSTANCE.getDownloader().restartDownload(ent.getId());
  }

  public static void resumeDownload(DbRecord ent) {
    AppContext.INSTANCE.getDownloader().resumeDownload(ent.getId());
  }

  public static void pauseDownload(DbRecord ent) {
    AppContext.INSTANCE.getDownloader().stopDownload(ent.getId());
  }

  public static void deleteDownload(DbRecord ent, Window window) {
    var ret =
        MessageBox.confirmWithCheckBox(
            window,
            StringResource.get("DEL_TITLE"),
            StringResource.get("DEL_SEL_TEXT"),
            StringResource.get("LBL_DELETE_FILE"));
    if (ret != MessageBoxResult.CANCEL) {
      //      XDMApp.getInstance()
      //          .deleteDownloads(
      //              items.stream().map(DownloadEntry::getId).collect(Collectors.toList()),
      //              ret == MessageBoxResult.YES_WITH_SELECTION);
    }
  }

  public static void stopQueue(String name) {
    //    String queueId = "";
    //    String[] arr = name.split(":");
    //    if (arr.length > 1) {
    //      queueId = arr[1].trim();
    //    }
    //    DownloadQueue q = XDMApp.getInstance().getQueueById(queueId);
    //    if (q != null) {
    //      q.stop();
    //    }
  }

  public static void showBatchPatternDialog() {
    new BatchPatternDialog().setVisible(true);
  }

  public static void openTranslationPage() {
    XDMUtils.browseURL("https://github.com/subhra74/xdm/wiki/Submitting-translations-for-XDM");
  }

  public static void openSupportPage() {
    XDMUtils.browseURL("https://github.com/subhra74/xdm/wiki");
  }

  public static void openBugReportPage() {
    XDMUtils.browseURL("https://github.com/subhra74/xdm/issues");
  }

  public static void optimizeRWin() {
    JComboBox<String> cmbLang =
        new JComboBox<>(
            new String[] {
              StringResource.get("LBL_NET_OPT_DEF"),
              StringResource.get("LBL_NET_OPT_64"),
              StringResource.get("LBL_NET_OPT_128"),
              StringResource.get("LBL_NET_OPT_256")
            });
    cmbLang.setSelectedIndex(0);

    String prompt = StringResource.get("LBL_NET_OPT_MSG");

    Object[] obj = new Object[2];
    obj[0] = prompt;
    obj[1] = cmbLang;

    switch (Config.getInstance().getTcpWindowSize()) {
      case 64:
        cmbLang.setSelectedIndex(1);
        break;
      case 128:
        cmbLang.setSelectedIndex(2);
        break;
      case 256:
        cmbLang.setSelectedIndex(3);
        break;
      default:
        cmbLang.setSelectedIndex(0);
    }

    if (JOptionPane.showOptionDialog(
            null,
            obj,
            StringResource.get("LBL_OPTIMIZE_NETWORK"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            null)
        == JOptionPane.OK_OPTION) {
      int index = cmbLang.getSelectedIndex();
      if (index != -1) {
        switch (index) {
          case 1:
            Config.getInstance().setTcpWindowSize(64);
            break;
          case 2:
            Config.getInstance().setTcpWindowSize(128);
            break;
          case 3:
            Config.getInstance().setTcpWindowSize(256);
            break;
          default:
            Config.getInstance().setTcpWindowSize(0);
        }
      }
    }
  }

  public static void showBatchDialog(AppWindow window) {
    //    window.restoreWindowIfNeeded();
    //    List<String> urlList = BatchDownloadWnd.getUrls();
    //    if (!urlList.isEmpty()) {
    //      // new BatchDownloadWnd(XDMUtils.toMetadata(urlList)).setVisible(true);
    //    } else {
    //      xdm.app.ui.components.MessageBox.show(
    //          window,
    //          StringResource.get("MENU_BATCH_DOWNLOAD"),
    //          StringResource.get("LBL_BATCH_EMPTY_CLIPBOARD"));
    //    }
  }

  public static void openFile(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (!items.isEmpty()) {
    //      openFile(items.get(0), window);
    //    }
  }

  public static void showLanguageDlg(AppWindow window) {
    Properties langMap = new Properties();
    InputStream in = null;
    try {
      in = StringResource.class.getResourceAsStream("/lang/map");
      if (in == null) {
        in = new FileInputStream("lang/map");
      }
      langMap.load(new InputStreamReader(in, Charset.forName("utf-8")));
    } catch (Exception e) {
      Logger.log(e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception e2) {
        }
      }
    }

    int index = 0;

    ArrayList<String> keyList = new ArrayList<>(langMap.stringPropertyNames());
    Vector<String> valList = new Vector<>();

    for (int i = 0; i < keyList.size(); i++) {
      String name = keyList.get(i);
      String val = langMap.getProperty(name);
      valList.add(val);
      if (name.equals(Config.getInstance().getLanguage())) {
        index = i;
      }
    }

    JComboBox<String> cmbLang = new JComboBox<>(valList);
    cmbLang.setSelectedIndex(index);

    String prompt = StringResource.get("MSG_LANG1");

    Object[] obj = new Object[3];
    obj[0] = prompt;
    obj[1] = cmbLang;
    obj[2] = StringResource.get("MSG_LANG2");

    if (JOptionPane.showOptionDialog(
            window,
            obj,
            StringResource.get("MSG_LANG1"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
            null,
            null,
            null)
        == JOptionPane.OK_OPTION) {
      index = cmbLang.getSelectedIndex();
      if (index != -1) {
        Config.getInstance().setLanguage(keyList.get(index));
      }
      String lang = langMap.getProperty(cmbLang.getSelectedItem() + "");
      if (lang != null) Config.getInstance().setLanguage(lang);
    }
  }

  public static void changeFile(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (items == null || items.isEmpty()) {
    //      return;
    //    }
    //    var ent = items.get(0);
    //    if (ent.getState() == XDMConstants.FINISHED) {
    //      return;
    //    }
    //    JFileChooser jfc = new JFileChooser();
    //    jfc.setSelectedFile(
    //        new File(
    //            XDMApp.getInstance().getOutputFolder(ent.getId()),
    //            XDMApp.getInstance().getOutputFile(ent.getId(), false)));
    //    if (jfc.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
    //      File f = jfc.getSelectedFile();
    //      ent.setFolder(f.getParent());
    //      ent.setFile(f.getName());
    //      XDMApp.getInstance().fileNameChanged(ent.getId());
    //    }
  }

  public static void openRefreshPage(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (items == null || items.isEmpty()) {
    //      return;
    //    }
    //    DownloadEntry ent = items.get(0);
    //    if (ent == null) {
    //      return;
    //    }
    //    if (!(ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED)) {
    //      return;
    //    }
    //    try {
    //      //      HttpMetadata md = HttpMetadata.load(ent.getId());
    //      //				RefreshUrlPage rp = RefreshUrlPage.getPage(this);
    //      //				rp.setDetails(md);
    //      //				rp.showPanel();
    //    } catch (Exception e2) {
    //      Logger.log(e2);
    //    }
  }

  public static void showProperties(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (items == null || items.isEmpty()) {
    //      return;
    //    }
    //    var ent = items.get(0);
    //    HttpMetadata md = HttpMetadata.load(ent.getId());
    //    HeaderCollection headers = md.getHeaders();
    //    String referer = "";
    //    StringBuilder cookies = new StringBuilder();
    //    Iterator<HttpHeader> cookieIt = headers.getAll();
    //    while (cookieIt.hasNext()) {
    //      HttpHeader header = cookieIt.next();
    //      if ("referer".equalsIgnoreCase(header.getName())) {
    //        referer = header.getValue();
    //      }
    //      if ("cookie".equalsIgnoreCase(header.getName())) {
    //        cookies.append(header.getValue() + "\n");
    //      }
    //    }
    //    String type = "HTTP";
    //    if (md instanceof DashMetadata) {
    //      type = "DASH";
    //    } else if (md instanceof HlsMetadata) {
    //      type = "HLS";
    //    } else if (md instanceof HdsMetadata) {
    //      type = "HDS";
    //    }

    //				propPage.setDetails(ent.getFile(), XDMApp.getInstance().getFolder(ent), ent.getSize(),
    // md.getUrl(),
    //						referer, ent.getDateStr(), cookies.toString(), type);
    //				propPage.showPanel();
  }

  public static void startQueue(String name) {
    //    String queueId = "";
    //    String[] arr = name.split(":");
    //    if (arr.length > 1) {
    //      queueId = arr[1].trim();
    //    }
    //    DownloadQueue q = XDMApp.getInstance().getQueueById(queueId);
    //    if (q != null) {
    //      q.start();
    //    }
  }

  public static void copyUrl(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (items == null || items.isEmpty()) {
    //      return;
    //    }
    //    DownloadEntry ent = items.get(0);
    //    XDMUtils.copyURL(XDMApp.getInstance().getURL(ent.getId()));
  }

  public static void showProgressWindow(AppWindow window) {
    //    var items = window.getSelectedDownloads();
    //    if (items == null || items.isEmpty()) {
    //      return;
    //    }
    //    DownloadEntry ent = items.get(0);
    //    XDMApp.getInstance().showPrgWnd(ent.getId());
  }

  public static void deleteCompleted(AppWindow window) {
    //    if (xdm.app.ui.components.MessageBox.confirm(
    //        window, StringResource.get("DEL_TITLE"), StringResource.get("DEL_FINISHED_TEXT"))) {
    //      XDMApp.getInstance().deleteCompleted();
    //    }
  }
}
