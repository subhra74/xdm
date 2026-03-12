package xdm.app.ui.components

import xdm.app.AppContext.downloader
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.MessageBoxResult
import xdm.app.RecordStatus
import xdm.app.ui.screens.AppWindow
import xdm.app.utils.getFileFolder
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
import xdm.core.util.Logger
//import xdman.Config
//import xdman.constants.MessageBoxResult
//import xdman.ui.components.BatchPatternDialog
import java.awt.Window
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.*
import javax.swing.JComboBox
import javax.swing.JOptionPane

object AppMenuHandler {
    fun openFile(ent: DbRecord?, window: Window?) {
        if (ent == null) return
        if (ent.status == RecordStatus.FINISHED) {
            Logger.info("Opening file for id: " + ent.id)
            val (fileName: String?, folder: String?) = getFileFolder(ent) ?: return
            if (fileName != null && folder != null) {
                try {
                    openFileExternal(fileName, folder);
                } catch (e: FileNotFoundException) {
                    Logger.info(e);
                    MessageBox.show(
                        window,
                        text("ERR_MSG_FILE_NOT_FOUND"),
                        text("ERR_MSG_FILE_NOT_FOUND_MSG")
                    );
                } catch (e: Exception) {
                    Logger.error(e);
                }
            }
        }
    }


    fun openFolder(ent: DbRecord?, window: Window?) {
        if (ent == null) return
        if (ent.status == RecordStatus.FINISHED) {
            Logger.error("Opening folder for id: " + ent.id)
            val (fileName: String?, folder: String?) = getFileFolder(ent) ?: return
            if (folder != null) {
                try {
                    Logger.info("Folder: $folder File: $fileName")
                    openFolderExternal(fileName, folder)
                } catch (e: FileNotFoundException) {
                    Logger.error(e)
                    MessageBox.show(
                        window,
                        text("ERR_MSG_FILE_NOT_FOUND"),
                        text("ERR_MSG_FILE_NOT_FOUND_MSG")
                    )
                } catch (e: Exception) {
                    Logger.error(e)
                }
            }
        }
    }

    fun restartDownload(ent: DbRecord?) {
        //    AppContext.INSTANCE.getDownloader().restartDownload(ent.getId());
    }

    fun resumeDownload(ent: DbRecord?) {
        if (ent == null) return
        downloader.resumeDownload(ent.id)
    }

    fun pauseDownload(ent: DbRecord?) {
        if (ent == null) return
        downloader.stopDownload(ent.id)
    }

    fun deleteDownload(ent: DbRecord?, window: Window?) {
        val ret =
            MessageBox.confirmWithCheckBox(
                window,
                text("DEL_TITLE"),
                text("DEL_SEL_TEXT"),
                text("LBL_DELETE_FILE")
            )
        if (ret != MessageBoxResult.CANCEL) {
            //      XDMApp.getInstance()
            //          .deleteDownloads(
            //              items.stream().map(DownloadEntry::getId).collect(Collectors.toList()),
            //              ret == MessageBoxResult.YES_WITH_SELECTION);
        }
    }

    fun stopQueue(name: String?) {
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

    fun showBatchPatternDialog() {
        //BatchPatternDialog().isVisible = true
    }

    fun openTranslationPage() {
        //XDMUtils.browseURL("https://github.com/subhra74/xdm/wiki/Submitting-translations-for-XDM")
    }

    fun openSupportPage() {
        //XDMUtils.browseURL("https://github.com/subhra74/xdm/wiki")
    }

    fun openBugReportPage() {
        //XDMUtils.browseURL("https://github.com/subhra74/xdm/issues")
    }

    fun optimizeRWin() {
//        val cmbLang =
//            JComboBox(
//                arrayOf(
//                    text("LBL_NET_OPT_DEF"),
//                    text("LBL_NET_OPT_64"),
//                    text("LBL_NET_OPT_128"),
//                    text("LBL_NET_OPT_256")
//                )
//            )
//        cmbLang.selectedIndex = 0
//
//        val prompt = text("LBL_NET_OPT_MSG")
//
//        val obj = arrayOfNulls<Any>(2)
//        obj[0] = prompt
//        obj[1] = cmbLang
//
//        when (Config.getInstance().tcpWindowSize) {
//            64 -> cmbLang.setSelectedIndex(1)
//            128 -> cmbLang.setSelectedIndex(2)
//            256 -> cmbLang.setSelectedIndex(3)
//            else -> cmbLang.setSelectedIndex(0)
//        }
//
//        if (JOptionPane.showOptionDialog(
//                null,
//                obj,
//                text("LBL_OPTIMIZE_NETWORK"),
//                JOptionPane.OK_CANCEL_OPTION,
//                JOptionPane.PLAIN_MESSAGE,
//                null,
//                null,
//                null
//            )
//            == JOptionPane.OK_OPTION
//        ) {
//            val index = cmbLang.selectedIndex
//            if (index != -1) {
//                when (index) {
//                    1 -> Config.getInstance().tcpWindowSize = 64
//                    2 -> Config.getInstance().tcpWindowSize = 128
//                    3 -> Config.getInstance().tcpWindowSize = 256
//                    else -> Config.getInstance().tcpWindowSize = 0
//                }
//            }
//        }
    }

    fun showBatchDialog(window: AppWindow?) {
        //    window.restoreWindowIfNeeded();
        //    List<String> urlList = BatchDownloadWnd.getUrls();
        //    if (!urlList.isEmpty()) {
        //      // new BatchDownloadWnd(XDMUtils.toMetadata(urlList)).setVisible(true);
        //    } else {
        //      xdm.app.ui.components.MessageBox.show(
        //          window,
        //          text("MENU_BATCH_DOWNLOAD"),
        //          text("LBL_BATCH_EMPTY_CLIPBOARD"));
        //    }
    }

    fun openFile(window: AppWindow?) {
        //    var items = window.getSelectedDownloads();
        //    if (!items.isEmpty()) {
        //      openFile(items.get(0), window);
        //    }
    }

    fun showLanguageDlg(window: AppWindow?) {
//        val langMap = Properties()
//        var `in`: InputStream? = null
//        try {
//            `in` = AppMenuHandler::class.java.getResourceAsStream("/lang/map")
//            if (`in` == null) {
//                `in` = FileInputStream("lang/map")
//            }
//            langMap.load(InputStreamReader(`in`, Charset.forName("utf-8")))
//        } catch (e: Exception) {
//            Logger.error(e)
//        } finally {
//            if (`in` != null) {
//                try {
//                    `in`.close()
//                } catch (e2: Exception) {
//                }
//            }
//        }
//
//        var index = 0
//
//        val keyList = ArrayList(langMap.stringPropertyNames())
//        val valList = Vector<String>()
//
//        for (i in keyList.indices) {
//            val name = keyList[i]
//            val `val` = langMap.getProperty(name)
//            valList.add(`val`)
//            if (name == Config.getInstance().language) {
//                index = i
//            }
//        }
//
//        val cmbLang = JComboBox(valList)
//        cmbLang.selectedIndex = index
//
//        val prompt = text("MSG_LANG1")
//
//        val obj = arrayOfNulls<Any>(3)
//        obj[0] = prompt
//        obj[1] = cmbLang
//        obj[2] = text("MSG_LANG2")
//
//        if (JOptionPane.showOptionDialog(
//                window,
//                obj,
//                text("MSG_LANG1"),
//                JOptionPane.OK_CANCEL_OPTION,
//                JOptionPane.PLAIN_MESSAGE,
//                null,
//                null,
//                null
//            )
//            == JOptionPane.OK_OPTION
//        ) {
//            index = cmbLang.selectedIndex
//            if (index != -1) {
//                Config.getInstance().language = keyList[index]
//            }
//            val lang = langMap.getProperty(cmbLang.selectedItem.toString() + "")
//            if (lang != null) Config.getInstance().language = lang
//        }
    }

    fun changeFile(window: AppWindow?) {
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

    fun openRefreshPage(window: AppWindow?) {
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

    fun showProperties(window: AppWindow?) {
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

    fun startQueue(name: String?) {
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

    fun copyUrl(window: AppWindow?) {
        //    var items = window.getSelectedDownloads();
        //    if (items == null || items.isEmpty()) {
        //      return;
        //    }
        //    DownloadEntry ent = items.get(0);
        //    XDMUtils.copyURL(XDMApp.getInstance().getURL(ent.getId()));
    }

    fun showProgressWindow(window: AppWindow?) {
        //    var items = window.getSelectedDownloads();
        //    if (items == null || items.isEmpty()) {
        //      return;
        //    }
        //    DownloadEntry ent = items.get(0);
        //    XDMApp.getInstance().showPrgWnd(ent.getId());
    }

    fun deleteCompleted(window: AppWindow?) {
        //    if (xdm.app.ui.components.MessageBox.confirm(
        //        window, text("DEL_TITLE"), text("DEL_FINISHED_TEXT"))) {
        //      XDMApp.getInstance().deleteCompleted();
        //    }
    }
}
