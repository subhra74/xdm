package xdm.app.ui.screens

import com.formdev.flatlaf.FlatLaf
import xdm.app.AppContext
import xdm.app.AppContext.app
import xdm.app.XDM_WINDOW_TITLE
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.OS
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.AppToolBar
import xdm.app.ui.components.FilterListPanel
import xdm.app.ui.components.MainListView
import xdm.app.ui.components.UpdatePanel
import xdm.app.update.UpdateChecker
import xdm.app.utils.applyMacOSWindowCustomizations
import xdm.app.utils.detectOS
import xdm.core.util.Logger
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

class AppWindow(image: Image) : JFrame(), ActionListener {
    private val listView = MainListView()
    private val updatePanel = UpdatePanel()

    init {
        title = XDM_WINDOW_TITLE
        iconImage = image
        val os = detectOS()
        if (os == OS.Windows) {
            getRootPane().putClientProperty("JRootPane.titleBarBackground", UIManager.getColor("Table.background"))
        } else if (os == OS.MacOS) {
            applyMacOSWindowCustomizations(image)
        }

        setWindowSizeAndPosition()
        initWindow()
        checkForUpdates()

        //ProgressWindow().isVisible = true
    }

    /**
     * Kicks off a background update check at startup; if a newer release is
     * found, reveals the update banner at the bottom of the window (on the EDT).
     */
    private fun checkForUpdates() {
        UpdateChecker.checkForUpdate { info ->
            if (info != null) {
                SwingUtilities.invokeLater {
                    updatePanel.showUpdate(info.latestVersion, info.downloadUrl)
                }
            }
        }
    }

    private fun initWindow() {
        val filterPanel = FilterListPanel(
            stateChanged = { listView.filterStateChanged(it) },
            categoryChanged = { listView.filterCategoryChanged(it) }
        )
        val toolbar = AppToolBar(
            { listView.searchTextChanged(it) },
            this,
            { key, asc -> listView.sort(key, asc) },
            { filterPanel.reloadCategories() }
        )
        toolbar.setMultiSelectView(false)
        listView.selectModeCallback = { toolbar.setMultiSelectView(it) }

        val panel = JPanel(BorderLayout(10, 0)).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(listView.component)
            border = EmptyBorder(7, 0, 0, 0)
            if (AppContext.config.theme == "light") {
                background = UIManager.getColor("Table.background")
            }
        }

        // Keep the black top border in the dark theme (as before); use FlatLaf's
        // border color in the light theme so it isn't a harsh black line.
        val topBorderColor = if (FlatLaf.isLafDark()) {
            Color.BLACK
        } else {
            UIManager.getColor("Component.borderColor") ?: Color.GRAY
        }
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            border = MatteBorder(1, 0, 0, 0, topBorderColor)
            dividerLocation = 180
            leftComponent = filterPanel.component
            rightComponent = panel
            if (AppContext.config.theme == "light") {
                background = UIManager.getColor("Table.background")
            }
        }
        add(splitPane, BorderLayout.CENTER)
        add(updatePanel, BorderLayout.SOUTH)

        ToolTipManager.sharedInstance().initialDelay = 500
    }

    fun updateDownloadInView(index: Int) {
        listView.rowUpdated(index)
    }

    fun deleteDownloadInView(index: Int) {
        listView.rowDeleted(index)
    }

    fun addDownloadInView(index: Int) {
        listView.rowAdded(index)
    }


    override fun actionPerformed(e: ActionEvent) {
        Logger.info("XDM", "Command: ${e.actionCommand}")
        if (e.source is JComponent) {
            val name: String = (e.source as JComponent).name ?: return

            when (name) {
                "TOOL_DOWNLOAD" -> {
                    app.addDownload(null)
                    return
                }

                "TOOL_CLEAR" -> {
                    clearDownloads()
                }
            }

            if (name.startsWith("STOP")) {
                AppMenuHandler.stopQueue(name)
            } else if (name.startsWith("START")) {
                AppMenuHandler.startQueue(name)
            } else if ("TOOL_DOWNLOAD" == name || "MENU_ADD_URL" == name) {
                app.addDownload(null)
            } else if ("PAUSE" == name || "MENU_PAUSE" == name) {
                // AppMenuHandler.pauseDownloads(this);
            } else if ("LBL_SHOW_PROGRESS" == name) {
                AppMenuHandler.showProgressWindow(this)
            } else if ("MENU_RESTART" == name) {
                //        AppMenuHandler.restartDownloads(this);
            } else if ("RESUME" == name || "MENU_RESUME" == name) {
                //        AppMenuHandler.resumeDownloads(this);
            } else if ("CTX_OPEN_FILE" == name) {
                AppMenuHandler.openFile(this)
            } else if ("CTX_OPEN_FOLDER" == name) {
                //        AppMenuHandler.openFolder(this);
            } else if ("MENU_EXIT" == name) {
                // XDMApp.getInstance().exit();
            } else if ("MENU_OPTIONS" == name || "OPTIONS" == name) {
                // SettingsPage.getInstance().showPanel(this, "PG_SETTINGS");
            } else if ("MENU_REFRESH_LINK" == name) {
                AppMenuHandler.openRefreshPage(this)
            } else if ("MENU_PROPERTIES" == name) {
                //AppMenuHandler.showProperties(this)
            } else if ("MENU_BROWSER_INT" == name) {
                // SettingsPage.getInstance().showPanel(this, "BTN_MONITORING");
            } else if ("MENU_SPEED_LIMITER" == name) {
                //        int ret = SpeedLimiter.getSpeedLimit();
                //        if (ret >= 0) {
                //          Config.getInstance().setSpeedLimit(ret);
                //        }
            } else if ("DESC_Q_TITLE" == name) {
                // SettingsPage.getInstance().showPanel(this, "Q_MAN");
            } else if ("TOOL_DELETE" == name) {
                Logger.info("Selected items: ${listView.selectedItems}")
                AppMenuHandler.deleteSelectedDownloads(listView.selectedItems, this)
            } else if ("MENU_DELETE_COMPLETED" == name) {
                AppMenuHandler.deleteCompleted(this)
            } else if ("MENU_ABOUT" == name) {
                //				AboutPage aboutPage = new AboutPage(this);
                //				aboutPage.showPanel();
            } else if ("CTX_SAVE_AS" == name) {
                AppMenuHandler.changeFile(this)
            } else if ("MENU_IMPORT" == name) {
                //        JFileChooser jfc = new JFileChooser();
                //        if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                //          File file = jfc.getSelectedFile();
                //          XDMApp.getInstance().loadDownloadList(file);
                //        }
            } else if ("MENU_EXPORT" == name) {
                //        JFileChooser jfc = new JFileChooser();
                //        if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                //          File file = jfc.getSelectedFile();
                //          XDMApp.getInstance().saveDownloadList(file);
                //        }
            } else if ("MENU_CONTENTS" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_WIKI_URL);
            } else if ("MENU_HOME_PAGE" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_HOME_URL);
            } else if ("MENU_UPDATE" == name) {
                //        XDMUtils.browseURL(XDMApp.APP_UPDATE_CHK_URL + XDMApp.APP_VERSION);
            } else if ("MENU_LANG" == name) {
                AppMenuHandler.showLanguageDlg(this)
            } else if ("MENU_BATCH_DOWNLOAD" == name) {
                AppMenuHandler.showBatchPatternDialog()
            } else if ("MENU_CLIP_ADD_MENU" == name) {
                AppMenuHandler.showBatchDialog(this)
            } else if ("LBL_OPTIMIZE_NETWORK" == name) {
                AppMenuHandler.optimizeRWin()
            } else if ("LBL_TRANSLATE" == name) {
                AppMenuHandler.openTranslationPage()
            } else if ("LBL_SUPPORT_PAGE" == name) {
                AppMenuHandler.openSupportPage()
            } else if ("LBL_REPORT_PROBLEM" == name) {
                AppMenuHandler.openBugReportPage()
            }
        }
    }

    private fun clearDownloads() {
        listView.clear()
    }

    private fun createMainMenu() {
        val bar = JMenuBar()

        val file = JMenu(text("MENU_FILE"))

        addMenuItem("MENU_ADD_URL", file)
        addMenuItem("MENU_VIDEO_DWN", file)
        addMenuItem("MENU_CLIP_ADD_MENU", file)
        addMenuItem("MENU_BATCH_DOWNLOAD", file)
        addMenuItem("MENU_DELETE_DWN", file)
        addMenuItem("MENU_DELETE_COMPLETED", file)
        addMenuItem("MENU_EXPORT", file)
        addMenuItem("MENU_IMPORT", file)
        addMenuItem("MENU_EXIT", file)

        val dwn = JMenu(text("MENU_DOWNLOAD"))

        addMenuItem("MENU_PAUSE", dwn)
        addMenuItem("MENU_RESUME", dwn)
        addMenuItem("MENU_RESTART", dwn)
        addMenuItem("DESC_Q_TITLE", dwn)

        val popupListener: PopupMenuListener =
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
//                    loadQueueMenu(startQMenu!!)
//                    loadQueueMenu(stopQMenu!!)
                }

                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}

                override fun popupMenuCanceled(e: PopupMenuEvent) {}
            }

//        startQMenu = addSubMenu("MENU_START_Q", dwn, popupListener)
//        stopQMenu = addSubMenu("MENU_STOP_Q", dwn, popupListener)

        val tools = JMenu(text("MENU_TOOLS"))

        addMenuItem("MENU_OPTIONS", tools)
        addMenuItem("MENU_REFRESH_LINK", tools)
        addMenuItem("MENU_PROPERTIES", tools)
        addMenuItem("MENU_SPEED_LIMITER", tools)
        addMenuItem("MENU_LANG", tools)
        addMenuItem("MENU_MEDIA_CONVERTER", tools)
        addMenuItem("LBL_OPTIMIZE_NETWORK", tools)
        addMenuItem("MENU_BROWSER_INT", tools)

        val help = JMenu(text("MENU_HELP"))
        addMenuItem("MENU_CONTENTS", help)
        addMenuItem("MENU_HOME_PAGE", help)
        addMenuItem("LBL_SUPPORT_PAGE", help)
        addMenuItem("LBL_REPORT_PROBLEM", help)
        addMenuItem("LBL_TRANSLATE", help)
        addMenuItem("MENU_UPDATE", help)
        addMenuItem("MENU_ABOUT", help)

        bar.add(file)
        bar.add(dwn)
        bar.add(tools)
        bar.add(help)

        jMenuBar = bar
    }

    private fun addMenuItem(id: String, menu: JComponent) {
        val mItem = JMenuItem(text(id))
        mItem.name = id
        mItem.addActionListener(this)
        menu.add(mItem)
    }

    private fun addSubMenu(id: String, parentMenu: JMenu, popupListener: PopupMenuListener): JMenu {
        val menu = JMenu(text(id))
        menu.name = id
        menu.addActionListener(this)
        menu.popupMenu.addPopupMenuListener(popupListener)
        parentMenu.add(menu)
        return menu
    }

    private fun setWindowSizeAndPosition() {
        setSize(800, 500)
        setLocationRelativeTo(null)
//        if (Config.getInstance().width < 0 || Config.getInstance().height < 0) setSize(800, 500)
//        if (Config.getInstance().x < 0 || Config.getInstance().y < 0) setLocationRelativeTo(null)
    }

    private fun loadQueueMenu(menu: JMenu) {
        if (menu.name == "MENU_START_Q") {
            loadStartQueueMenu(menu)
        } else if (menu.name == "MENU_STOP_Q") {
            loadStopQueueMenu(menu)
        }
    }

    private fun loadStopQueueMenu(menu: JMenu) {
        //    menu.removeAll();
        //    ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
        //    for (int i = 0; i < queues.size(); i++) {
        //      DownloadQueue q = queues.get(i);
        //      if (q.isRunning()) {
        //        JMenuItem mitem = new JMenuItem(q.getName());
        //        mitem.setForeground(ColorResource.getLightFontColor());
        //        mitem.setName("STOP:" + q.getQueueId());
        //        mitem.addActionListener(this);
        //        menu.add(mitem);
        //      }
        //    }
    }

    private fun loadStartQueueMenu(menu: JMenu) {
        //    menu.removeAll();
        //    ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
        //    for (int i = 0; i < queues.size(); i++) {
        //      DownloadQueue q = queues.get(i);
        //      if (!q.isRunning()) {
        //        JMenuItem mitem = new JMenuItem(q.getName());
        //        mitem.setForeground(ColorResource.getLightFontColor());
        //        mitem.setName("START:" + q.getQueueId());
        //        mitem.addActionListener(this);
        //        menu.add(mitem);
        //      }
        //    }
    }

    private fun createPopupMenu() {
//        popupCtx = JPopupMenu()
//        addMenuItem("CTX_OPEN_FILE", popupCtx!!)
//        addMenuItem("CTX_OPEN_FOLDER", popupCtx!!)
//        addMenuItem("CTX_SAVE_AS", popupCtx!!)
//        addMenuItem("MENU_PAUSE", popupCtx!!)
//        addMenuItem("MENU_RESUME", popupCtx!!)
//        addMenuItem("MENU_DELETE_DWN", popupCtx!!)
//        addMenuItem("MENU_REFRESH_LINK", popupCtx!!)
//        addMenuItem("LBL_SHOW_PROGRESS", popupCtx!!)
//        addMenuItem("CTX_COPY_URL", popupCtx!!)
//        addMenuItem("CTX_COPY_FILE", popupCtx!!)
//        addMenuItem("MENU_PROPERTIES", popupCtx!!)
        //    popupCtx.setInvoker(listView.getComponent());
        //    listView.installPopupMenu(popupCtx, this);
    }

    val selectedDownloads: List<DbRecord>
        get() = emptyList() // listView.getSelectedItems();

}
