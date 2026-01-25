package xdm.app.ui.screens;

import java.awt.*;
import java.awt.desktop.AppReopenedListener;
import java.awt.event.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.*;

import com.formdev.flatlaf.util.SystemInfo;
import xdm.app.AppContext;
import xdm.app.constants.AppConstants;
//import xdm.app.models.DownloadEntry;
import xdm.app.data.DbRecord;
import xdm.app.ui.components.*;
import xdman.Config;
// import xdman.XDMApp;
import xdman.ui.res.*;
import xdman.util.*;

public class AppWindow extends JFrame implements ActionListener {
  private MainListView listView;
  private JTextField txtSearch;
  private JPopupMenu popupCtx;
  private JMenu startQMenu, stopQMenu;
  private int windowState;

  public AppWindow(Image image) {
    if (SystemInfo.isWindows) {
      getRootPane()
          .putClientProperty(
              "JRootPane.titleBarBackground", UIManager.getColor("Table.background"));
    }
    setIconImage(image);
    if (SystemInfo.isMacOS) {
      applyMacOSWindowCustomizations(image);
    }
    setTitle(AppConstants.XDM_WINDOW_TITLE);
    setWindowSizeAndPosition();
    initWindow();
  }

  private void initWindow() {
    this.listView = new MainListView();
    //    this.createPopupMenu();
    //    this.listView.installPopupMenu(this.popupCtx);
    var filterPanel = new FilterListPanel();
    var toolbar = new AppToolBar(s -> {}, this);
    toolbar.setMultiSelectView(false);
    listView.setSelectModeCallback(toolbar::setMultiSelectView);
    //    var filterPanel =
    //        new FilterPanel(
    //            f -> {
    //              toolbar.updateButtons(Collections.emptyList());
    //              // listView.setFilterItem(f);
    //            });
    // this.listView.setSelectionCallback(list ->
    // toolbar.updateButtons(listView.getSelectedItems()));

    var panel = new JPanel(new BorderLayout(10, 0));
    panel.add(toolbar.getComponent(), BorderLayout.NORTH);
    panel.add(listView.getComponent());
    panel.setBorder(new EmptyBorder(7, 0, 0, 0));
    var splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    splitPane.setBorder(new MatteBorder(1, 0, 0, 0, Color.BLACK));
    splitPane.setDividerLocation(180);
    splitPane.setLeftComponent(filterPanel.getComponent());
    splitPane.setRightComponent(panel);
    add(splitPane);

    // createPopupMenu();

    ToolTipManager.sharedInstance().setInitialDelay(500);

    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent e) {
            // listView.focus();
            windowState = AppWindow.this.getExtendedState();
          }

          @Override
          public void windowStateChanged(WindowEvent e) {
            if (AppWindow.this.getExtendedState() != JFrame.ICONIFIED) {
              windowState = AppWindow.this.getExtendedState();
            }
          }
        });
  }

  public void updateDownloadInView(int index) {
    this.listView.rowUpdated(index);
  }

  public void addDownloadInView(int index) {
    this.listView.rowAdded(index);
  }

  public void restoreWindowIfNeeded() {
    //    if (this.getExtendedState() == JFrame.ICONIFIED) {
    //      this.setExtendedState(this.windowState);
    //    }
    if (!this.isVisible()) {
      setVisible(true);
    }
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    if (e.getSource() instanceof JComponent c) {
      var name = c.getName();
      if (name == null) {
        return;
      }

      switch (name) {
        case "TOOL_DOWNLOAD":
          AppContext.INSTANCE.getApp().addDownload(null);
          return;
      }

      if (name.startsWith("STOP")) {
        AppMenuHandler.stopQueue(name);
      } else if (name.startsWith("START")) {
        AppMenuHandler.startQueue(name);
      } else if ("TOOL_DOWNLOAD".equals(name) || "MENU_ADD_URL".equals(name)) {
        AppContext.INSTANCE.getApp().addDownload(null);
      } else if ("PAUSE".equals(name) || "MENU_PAUSE".equals(name)) {
        // AppMenuHandler.pauseDownloads(this);
      } else if ("CTX_COPY_URL".equals(name)) {
        AppMenuHandler.copyUrl(this);
      } else if ("LBL_SHOW_PROGRESS".equals(name)) {
        AppMenuHandler.showProgressWindow(this);
      } else if ("MENU_RESTART".equals(name)) {
        //        AppMenuHandler.restartDownloads(this);
      } else if ("RESUME".equals(name) || "MENU_RESUME".equals(name)) {
        //        AppMenuHandler.resumeDownloads(this);
      } else if ("CTX_OPEN_FILE".equals(name)) {
        AppMenuHandler.openFile(this);
      } else if ("CTX_OPEN_FOLDER".equals(name)) {
        //        AppMenuHandler.openFolder(this);
      } else if ("MENU_EXIT".equals(name)) {
        // XDMApp.getInstance().exit();
      } else if ("MENU_OPTIONS".equals(name) || "OPTIONS".equals(name)) {
        // SettingsPage.getInstance().showPanel(this, "PG_SETTINGS");
      } else if ("MENU_REFRESH_LINK".equals(name)) {
        AppMenuHandler.openRefreshPage(this);
      } else if ("MENU_PROPERTIES".equals(name)) {
        AppMenuHandler.showProperties(this);
      } else if ("MENU_BROWSER_INT".equals(name)) {
        // SettingsPage.getInstance().showPanel(this, "BTN_MONITORING");
      } else if ("MENU_SPEED_LIMITER".equals(name)) {
        //        int ret = SpeedLimiter.getSpeedLimit();
        //        if (ret >= 0) {
        //          Config.getInstance().setSpeedLimit(ret);
        //        }
      } else if ("DESC_Q_TITLE".equals(name)) {
        // SettingsPage.getInstance().showPanel(this, "Q_MAN");
      } else if ("MENU_DELETE_DWN".equals(name)
          || "DELETE".equals(name)
          || "DESC_DEL".equals(name)) {
        //        AppMenuHandler.deleteDownloads(this);
      } else if ("MENU_DELETE_COMPLETED".equals(name)) {
        AppMenuHandler.deleteCompleted(this);
      } else if ("MENU_ABOUT".equals(name)) {
        //				AboutPage aboutPage = new AboutPage(this);
        //				aboutPage.showPanel();
      } else if ("CTX_SAVE_AS".equals(name)) {
        AppMenuHandler.changeFile(this);
      } else if ("MENU_IMPORT".equals(name)) {
        //        JFileChooser jfc = new JFileChooser();
        //        if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
        //          File file = jfc.getSelectedFile();
        //          XDMApp.getInstance().loadDownloadList(file);
        //        }
      } else if ("MENU_EXPORT".equals(name)) {
        //        JFileChooser jfc = new JFileChooser();
        //        if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
        //          File file = jfc.getSelectedFile();
        //          XDMApp.getInstance().saveDownloadList(file);
        //        }
      } else if ("MENU_CONTENTS".equals(name)) {
        //        XDMUtils.browseURL(XDMApp.APP_WIKI_URL);
      } else if ("MENU_HOME_PAGE".equals(name)) {
        //        XDMUtils.browseURL(XDMApp.APP_HOME_URL);
      } else if ("MENU_UPDATE".equals(name)) {
        //        XDMUtils.browseURL(XDMApp.APP_UPDATE_CHK_URL + XDMApp.APP_VERSION);
      } else if ("MENU_LANG".equals(name)) {
        AppMenuHandler.showLanguageDlg(this);
      } else if ("MENU_BATCH_DOWNLOAD".equals(name)) {
        AppMenuHandler.showBatchPatternDialog();
      } else if ("MENU_CLIP_ADD_MENU".equals(name)) {
        AppMenuHandler.showBatchDialog(this);
      } else if ("LBL_OPTIMIZE_NETWORK".equals(name)) {
        AppMenuHandler.optimizeRWin();
      } else if ("LBL_TRANSLATE".equals(name)) {
        AppMenuHandler.openTranslationPage();
      } else if ("LBL_SUPPORT_PAGE".equals(name)) {
        AppMenuHandler.openSupportPage();
      } else if ("LBL_REPORT_PROBLEM".equals(name)) {
        AppMenuHandler.openBugReportPage();
      }
    }
  }

  private void createMainMenu() {

    JMenuBar bar = new JMenuBar();

    JMenu file = new JMenu(StringResource.get("MENU_FILE"));

    addMenuItem("MENU_ADD_URL", file);
    addMenuItem("MENU_VIDEO_DWN", file);
    addMenuItem("MENU_CLIP_ADD_MENU", file);
    addMenuItem("MENU_BATCH_DOWNLOAD", file);
    addMenuItem("MENU_DELETE_DWN", file);
    addMenuItem("MENU_DELETE_COMPLETED", file);
    addMenuItem("MENU_EXPORT", file);
    addMenuItem("MENU_IMPORT", file);
    addMenuItem("MENU_EXIT", file);

    JMenu dwn = new JMenu(StringResource.get("MENU_DOWNLOAD"));

    addMenuItem("MENU_PAUSE", dwn);
    addMenuItem("MENU_RESUME", dwn);
    addMenuItem("MENU_RESTART", dwn);
    addMenuItem("DESC_Q_TITLE", dwn);

    PopupMenuListener popupListener =
        new PopupMenuListener() {

          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            loadQueueMenu(startQMenu);
            loadQueueMenu(stopQMenu);
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}

          @Override
          public void popupMenuCanceled(PopupMenuEvent e) {}
        };

    startQMenu = addSubMenu("MENU_START_Q", dwn, popupListener);
    stopQMenu = addSubMenu("MENU_STOP_Q", dwn, popupListener);

    JMenu tools = new JMenu(StringResource.get("MENU_TOOLS"));

    addMenuItem("MENU_OPTIONS", tools);
    addMenuItem("MENU_REFRESH_LINK", tools);
    addMenuItem("MENU_PROPERTIES", tools);
    addMenuItem("MENU_SPEED_LIMITER", tools);
    addMenuItem("MENU_LANG", tools);
    addMenuItem("MENU_MEDIA_CONVERTER", tools);
    addMenuItem("LBL_OPTIMIZE_NETWORK", tools);
    addMenuItem("MENU_BROWSER_INT", tools);

    JMenu help = new JMenu(StringResource.get("MENU_HELP"));
    addMenuItem("MENU_CONTENTS", help);
    addMenuItem("MENU_HOME_PAGE", help);
    addMenuItem("LBL_SUPPORT_PAGE", help);
    addMenuItem("LBL_REPORT_PROBLEM", help);
    addMenuItem("LBL_TRANSLATE", help);
    addMenuItem("MENU_UPDATE", help);
    addMenuItem("MENU_ABOUT", help);

    bar.add(file);
    bar.add(dwn);
    bar.add(tools);
    bar.add(help);

    setJMenuBar(bar);
  }

  private void addMenuItem(String id, JComponent menu) {
    var mItem = new JMenuItem(StringResource.get(id));
    mItem.setName(id);
    mItem.addActionListener(this);
    menu.add(mItem);
  }

  private JMenu addSubMenu(String id, JMenu parentMenu, PopupMenuListener popupListener) {
    JMenu menu = new JMenu(StringResource.get(id));
    menu.setName(id);
    menu.addActionListener(this);
    menu.getPopupMenu().addPopupMenuListener(popupListener);
    parentMenu.add(menu);
    return menu;
  }

  private void setWindowSizeAndPosition() {
    if (Config.getInstance().getWidth() < 0 || Config.getInstance().getHeight() < 0)
      setSize(800, 500);
    if (Config.getInstance().getX() < 0 || Config.getInstance().getY() < 0)
      setLocationRelativeTo(null);
  }

  private void loadQueueMenu(JMenu menu) {
    if (menu.getName().equals("MENU_START_Q")) {
      loadStartQueueMenu(menu);
    } else if (menu.getName().equals("MENU_STOP_Q")) {
      loadStopQueueMenu(menu);
    }
  }

  private void loadStopQueueMenu(JMenu menu) {
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

  private void loadStartQueueMenu(JMenu menu) {
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

  private void createPopupMenu() {
    popupCtx = new JPopupMenu();
    addMenuItem("CTX_OPEN_FILE", popupCtx);
    addMenuItem("CTX_OPEN_FOLDER", popupCtx);
    addMenuItem("CTX_SAVE_AS", popupCtx);
    addMenuItem("MENU_PAUSE", popupCtx);
    addMenuItem("MENU_RESUME", popupCtx);
    addMenuItem("MENU_DELETE_DWN", popupCtx);
    addMenuItem("MENU_REFRESH_LINK", popupCtx);
    addMenuItem("LBL_SHOW_PROGRESS", popupCtx);
    addMenuItem("CTX_COPY_URL", popupCtx);
    addMenuItem("CTX_COPY_FILE", popupCtx);
    addMenuItem("MENU_PROPERTIES", popupCtx);
    //    popupCtx.setInvoker(listView.getComponent());
    //    listView.installPopupMenu(popupCtx, this);
  }

  public List<DbRecord> getSelectedDownloads() {
    return Collections.emptyList(); // listView.getSelectedItems();
  }

  private void applyMacOSWindowCustomizations(Image image) {
    /* Set Dock icon in macOS */
    try {
      Taskbar.getTaskbar().setIconImage(image);
    } catch (final UnsupportedOperationException | SecurityException e) {
      // Nothing to do
    }
    try {
      if (SystemInfo.isMacFullWindowContentSupported) {
        this.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
      }
    } catch (Exception ex) {
      // Nothing to do
    }
    try {
      Desktop.getDesktop()
          .addAppEventListener(
              (AppReopenedListener) e -> AppContext.INSTANCE.getApp().showAppWindow());
    } catch (Exception ex) {
      // Nothing to do
    }
  }
}
