package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import xdman.Config;
import xdman.DownloadEntry;
import xdman.DownloadQueue;
import xdman.MonitoringListener;
import xdman.QueueManager;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpHeader;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.FFmpegDownloader;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class MainWindow extends XDMFrame implements ActionListener {
	private static final long serialVersionUID = -3119522563540700138L;

	CustomButton btnTabArr[];
	CustomButton btnSort;
	CustomButton btnQueue;
	JTextField txtSearch;
	JMenuItem[] sortItems;
	String[][] sortStatusText;
	JLabel[] lblCatArr;
	SidePanel sp;
	DownloadListView lv;
	JPopupMenu popupCtx;
	JMenu startQMenu, stopQMenu, convertMenu;

	JPanel toolbar;
	UpdateNotifyPanel updateNotifyPanel;
	JLabel btnMonitoring;

	public MainWindow() {
		setTitle(StringResource.get("WINDOW_TITLE"));
		setWindowSizeAndPosition();
		initWindow();
		if (Config.getInstance().isFirstRun()) {
			SettingsPage.getInstance().showPanel(this, "BTN_MONITORING");
		}
		showNotification();
		Config.getInstance().addConfigListener(new MonitoringListener() {

			@Override
			public void configChanged() {
				btnMonitoring.setIcon(Config.getInstance().isBrowserMonitoringEnabled() ? ImageResource.get("on.png")
						: ImageResource.get("off.png"));
			}
		});
	}

	@Override
	protected void registerTitlePanel(JPanel panel) {
		showTwitterIcon = true;
		showFBIcon = true;
		fbUrl = "https://www.facebook.com/XDM.subhra74/";
		twitterUrl = "https://twitter.com/XDM_subhra74";
		super.registerTitlePanel(panel);
	}

	private String getQueueName(String str) {
		if (str == null) {
			return "ALL";
		}
		int index = str.indexOf(":");
		if (index > 0) {
			return str.substring(index + 1);
		}
		return "ALL";
	}

	private void filterQueue(String name, Config config) {
		String qName = getQueueName(name);
		config.setQueueIdFilter(qName);
		System.out.println("filter queue name: " + qName);

		filter();
		String text = StringResource.get("LBL_ALL_QUEUE");
		if (!qName.equals("ALL")) {
			if (StringUtils.isNullOrEmptyOrBlank(qName)) {
				text = QueueManager.getInstance().getDefaultQueue().getName();
			} else {
				text = QueueManager.getInstance().getQueueById(qName).getName();
			}
		}
		btnQueue.setText(text);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		Config config = Config.getInstance();
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if (name == null) {
				return;
			}
			if (name.startsWith("Q_VIEW:")) {
				filterQueue(name, config);
			}
			if (name.startsWith("STOP")) {
				stopQueue(name);
			} else if (name.equals("OPT_UPDATE_FFMPEG")) {
				updateFFmpeg();
			} else if (name.startsWith("OPT_CONVERT")) {
				convert();
			} else if (name.startsWith("START")) {
				startQueue(name);
			} else if ("ADD_URL".equals(name) || "MENU_ADD_URL".equals(name)) {
				XDMApp.getInstance().addDownload(null, null);
			} else if ("ALL_DOWNLOADS".equals(name)) {
				tabClicked(e);
				config.setStateFilter(XDMConstants.ALL);
				filter();
			} else if ("ALL_UNFINISHED".equals(name)) {
				tabClicked(e);
				config.setStateFilter(XDMConstants.UNFINISHED);
				filter();
			} else if ("ALL_FINISHED".equals(name)) {
				tabClicked(e);
				config.setStateFilter(XDMConstants.FINISHED);
				filter();
			} else if ("PAUSE".equals(name) || "MENU_PAUSE".equals(name)) {
				pauseDownloads();
			} else if ("CTX_COPY_URL".equals(name)) {
				String[] ids = lv.getSelectedIds();
				if (ids.length > 0) {
					String id = ids[0];
					XDMUtils.copyURL(XDMApp.getInstance().getURL(id));
				}
			} else if ("LBL_SHOW_PROGRESS".equals(name)) {
				String[] ids = lv.getSelectedIds();
				if (ids.length > 0) {
					String id = ids[0];
					XDMApp.getInstance().showPrgWnd(id);
				}
			} else if ("MENU_RESTART".equals(name)) {
				String[] ids = lv.getSelectedIds();
				for (int i = 0; i < ids.length; i++) {
					XDMApp.getInstance().restartDownload(ids[i]);
				}
			} else if ("RESUME".equals(name) || "MENU_RESUME".equals(name)) {
				String[] ids = lv.getSelectedIds();
				for (int i = 0; i < ids.length; i++) {
					XDMApp.getInstance().resumeDownload(ids[i], true);
				}
			} else if ("CTX_OPEN_FILE".equals(name)) {
				openFile();
			} else if ("CTX_OPEN_FOLDER".equals(name)) {
				String[] ids = lv.getSelectedIds();
				if (ids.length > 0) {
					String id = ids[0];
					openFolder(id);
				}
			} else if ("MENU_EXIT".equals(name)) {
				XDMApp.getInstance().exit();
			} else if ("0".equals(name)) {
				config.setSortField(0);
				filter();
			} else if ("1".equals(name)) {
				config.setSortField(1);
				filter();
			} else if ("2".equals(name)) {
				config.setSortField(2);
				filter();
			} else if ("3".equals(name)) {
				config.setSortField(3);
				filter();
			} else if ("4".equals(name)) {
				config.setSortAsc(true);
				filter();
			} else if ("5".equals(name)) {
				config.setSortAsc(false);
				filter();
			} else if ("BTN_SEARCH".equals(name)) {
				config.setSearchText(txtSearch.getText());
				filter();
			} else if ("CAT_DOCUMENTS".equals(name)) {
				config.setCategoryFilter(XDMConstants.DOCUMENTS);
				updateSidePanel((JLabel) e.getSource());
			} else if ("CAT_COMPRESSED".equals(name)) {
				config.setCategoryFilter(XDMConstants.COMPRESSED);
				updateSidePanel((JLabel) e.getSource());
			} else if ("CAT_MUSIC".equals(name)) {
				config.setCategoryFilter(XDMConstants.MUSIC);
				updateSidePanel((JLabel) e.getSource());
			} else if ("CAT_PROGRAMS".equals(name)) {
				config.setCategoryFilter(XDMConstants.PROGRAMS);
				updateSidePanel((JLabel) e.getSource());
			} else if ("CAT_VIDEOS".equals(name)) {
				config.setCategoryFilter(XDMConstants.VIDEO);
				updateSidePanel((JLabel) e.getSource());
			} else if ("CAT_ALL".equals(name)) {
				config.setCategoryFilter(XDMConstants.ALL);
				updateSidePanel((JLabel) e.getSource());
			} else if ("MENU_CLIP_ADD".equals(name)) {
				int ret = MessageBox.show(this, "sample title",
						"sample textdgdfgdfgdfghdfh gfhsdgh gfgfh dfgdfqwewrqwerwerqwerqwerwerwqerqwerqwerqwerwerwegfterj jgh ker gwekl hwgklerhg ek hrkjlwhlk kj hgeklgh jkle herklj gheklwerjgh sample textdgdfgdfgdfghdfh gfhsdgh gfgfh dfgdfqwewrqwerwerqwerqwerwerwqerqwerqwerqwerwerwegfterj jgh ker gwekl hwgklerhg ek hrkjlwhlk kj hgeklgh jkle herklj gheklwerjgh",
						MessageBox.OK_OPTION, MessageBox.OK);
				System.out.println("After: " + ret);
				// new DownloadCompleteWnd().setVisible(true);
			} else if ("MENU_OPTIONS".equals(name) || "OPTIONS".equals(name)) {
				SettingsPage.getInstance().showPanel(this, "PG_SETTINGS");
			} else if ("MENU_REFRESH_LINK".equals(name)) {
				openRefreshPage();
			} else if ("MENU_PROPERTIES".equals(name)) {
				showProperties();
			} else if ("MENU_BROWSER_INT".equals(name)) {
				SettingsPage.getInstance().showPanel(this, "BTN_MONITORING");
			} else if ("MENU_SPEED_LIMITER".equals(name)) {
				int ret = SpeedLimiter.getSpeedLimit();
				if (ret >= 0) {
					Config.getInstance().setSpeedLimit(ret);
				}
			} else if ("DESC_Q_TITLE".equals(name)) {
				SettingsPage.getInstance().showPanel(this, "Q_MAN");
			} else if ("MENU_MEDIA_CONVERTER".equals(name)) {
				convert();
			} else if ("MENU_DELETE_DWN".equals(name) || "DELETE".equals(name)) {
				if (MessageBox.show(this, StringResource.get("DEL_TITLE"), StringResource.get("DEL_SEL_TEXT"),
						MessageBox.YES_NO_OPTION, MessageBox.YES,
						StringResource.get("LBL_DELETE_FILE")) == MessageBox.YES) {
					String[] ids = lv.getSelectedIds();
					ArrayList<String> idList = new ArrayList<String>();
					for (int i = 0; i < ids.length; i++) {
						idList.add(ids[i]);
					}
					XDMApp.getInstance().deleteDownloads(idList, MessageBox.isChecked());
				}
			} else if ("MENU_DELETE_COMPLETED".equals(name)) {
				if (MessageBox.show(this, StringResource.get("DEL_TITLE"), StringResource.get("DEL_FINISHED_TEXT"),
						MessageBox.YES_NO_OPTION, MessageBox.YES) == MessageBox.YES) {
					XDMApp.getInstance().deleteCompleted();
				}
			} else if ("MENU_ABOUT".equals(name)) {
				AboutPage aboutPage = new AboutPage(this);
				aboutPage.showPanel();
			} else if ("CTX_SAVE_AS".equals(name)) {
				String[] ids = lv.getSelectedIds();
				if (ids.length > 0) {
					String id = ids[0];
					changeFile(id);
				}
			} else if ("MENU_VIDEO_DWN".equals(name)) {
				showVideoDwnDlg();
			} else if ("MENU_IMPORT".equals(name)) {
				JFileChooser jfc = new JFileChooser();
				if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
					File file = jfc.getSelectedFile();
					XDMApp.getInstance().loadDownloadList(file);
				}
			} else if ("MENU_EXPORT".equals(name)) {
				JFileChooser jfc = new JFileChooser();
				if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
					File file = jfc.getSelectedFile();
					XDMApp.getInstance().saveDownloadList(file);
				}
			} else if ("MENU_CONTENTS".equals(name)) {
				XDMUtils.browseURL("http://xdman.sourceforge.net/#help");
			} else if ("MENU_HOME_PAGE".equals(name)) {
				XDMUtils.browseURL("http://xdman.sourceforge.net/");
			} else if ("MENU_UPDATE".equals(name)) {
				XDMUtils.browseURL("http://xdman.sourceforge.net/update/update.php?ver=" + XDMApp.APP_VERSION);
			} else if ("MENU_LANG".equals(name)) {
				showLanguageDlg();
			} else if ("MENU_BATCH_DOWNLOAD".equals(name)) {
				showBatchPatternDialog();
			} else if ("MENU_CLIP_ADD_MENU".equals(name)) {
				showBatchDialog();
			} else if ("DWN_PREVIEW".equals(name)) {
				String[] ids = lv.getSelectedIds();
				if (ids == null || ids.length < 1) {
					return;
				}
				DownloadEntry ent = XDMApp.getInstance().getEntry(ids[0]);
				if (ent == null)
					return;
				if (ent.getState() != XDMConstants.FINISHED) {
					XDMApp.getInstance().openPreview(ent.getId());
				}
			} else if ("LBL_OPTIMIZE_NETWORK".equals(name)) {
				optimizeRWin();
			} else if ("LBL_TRANSLATE".equals(name)) {
				openTranslationPage();
			} else if ("LBL_SUPPORT_PAGE".equals(name)) {
				openSupportPage();
			} else if ("LBL_REPORT_PROBLEM".equals(name)) {
				openBugReportPage();
			}
		}
	}

	private void updateSidePanel(JLabel lbl) {
		for (int i = 0; i < lblCatArr.length; i++) {
			if (lbl == lblCatArr[i]) {
				lblCatArr[i].setBackground(ColorResource.getActiveTabColor());
				lblCatArr[i].setOpaque(true);
			} else {
				lblCatArr[i].setOpaque(false);
			}
		}
		lv.refresh();
		sp.repaint();
	}

	private void filter() {
		lv.refresh();
		updateSortMenu();
		btnSort.setText(sortStatusText[Config.getInstance().getSortField()][Config.getInstance().getSortAsc() ? 1 : 0]);
	}

	private JPanel createToolbar() {
		JPanel p = new JPanel(new BorderLayout());
		Box toolBox = Box.createHorizontalBox();
		toolBox.add(Box.createRigidArea(new Dimension(scale(20), scale(60))));
		toolBox.setBackground(ColorResource.getTitleColor());
		toolBox.setOpaque(true);

		JButton btn1 = createToolButton("ADD_URL", "tool_add.png");
		btn1.setToolTipText(StringResource.get("MENU_ADD_URL"));
		toolBox.add(btn1);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn2 = createToolButton("DELETE", "tool_del.png");
		btn2.setToolTipText(StringResource.get("MENU_DELETE_DWN"));
		toolBox.add(btn2);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn3 = createToolButton("PAUSE", "tool_pause.png");
		btn3.setToolTipText(StringResource.get("MENU_PAUSE"));
		toolBox.add(btn3);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn4 = createToolButton("RESUME", "tool_resume.png");
		btn4.setToolTipText(StringResource.get("MENU_RESUME"));
		toolBox.add(btn4);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn5 = createToolButton("OPTIONS", "tool_settings.png");
		btn5.setToolTipText(StringResource.get("TITLE_SETTINGS"));
		toolBox.add(btn5);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn6 = createToolButton("MENU_VIDEO_DWN", "tool_video.png");
		btn6.setToolTipText(StringResource.get("MENU_VIDEO_DWN"));
		toolBox.add(btn6);

		toolBox.add(Box.createRigidArea(new Dimension(scale(10), scale(10))));

		JButton btn7 = createToolButton("MENU_MEDIA_CONVERTER", "tool_convert.png");
		btn7.setToolTipText(StringResource.get("MENU_MEDIA_CONVERTER"));
		toolBox.add(btn7);
		toolBox.add(Box.createHorizontalGlue());

		btnMonitoring = new JLabel(ImageResource.get("on.png"));
		// btnMonitoring.setForeground(Color.WHITE);
		btnMonitoring.setIconTextGap(scale(15));
		btnMonitoring.putClientProperty("xdmbutton.norollover", "true");
		// btnMonitoring.setBackground(ColorResource.getTitleColor());
		btnMonitoring.setName("BROWSER_MONITORING");
		btnMonitoring.setText(StringResource.get("BROWSER_MONITORING"));
		btnMonitoring.setHorizontalTextPosition(JButton.LEADING);
		btnMonitoring.setFont(FontResource.getBigFont());

		btnMonitoring.setIcon(Config.getInstance().isBrowserMonitoringEnabled() ? ImageResource.get("on.png")
				: ImageResource.get("off.png"));

		btnMonitoring.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				toggleMonitoring((JLabel) e.getSource());
			}
		});
		toolBox.add(btnMonitoring);
		toolBox.add(Box.createRigidArea(new Dimension(scale(25), scale(10))));
		p.add(toolBox);
		return p;
	}

	private JButton createToolButton(String name, String icon) {
		CustomButton btn = new CustomButton(ImageResource.get(icon));
		btn.setPressedBackground(ColorResource.getDarkPressedColor());
		btn.setRolloverBackground(Color.DARK_GRAY);
		btn.setBorderPainted(false);
		btn.addActionListener(this);
		btn.setName(name);
		btn.setBackground(ColorResource.getTitleColor());
		btn.setMargin(new Insets(0, 0, 0, 0));
		return btn;
	}

	private SidePanel createSidePanel() {
		sp = new SidePanel();
		sp.setLayout(null);
		sp.setPreferredSize(new Dimension(scale(150), scale(250)));

		lblCatArr = new JLabel[6];

		lblCatArr[0] = createCategoryLabel("CAT_ALL");
		lblCatArr[1] = createCategoryLabel("CAT_DOCUMENTS");
		lblCatArr[2] = createCategoryLabel("CAT_COMPRESSED");
		lblCatArr[3] = createCategoryLabel("CAT_MUSIC");
		lblCatArr[4] = createCategoryLabel("CAT_VIDEOS");
		lblCatArr[5] = createCategoryLabel("CAT_PROGRAMS");

		lblCatArr[0].setBackground(ColorResource.getActiveTabColor());
		lblCatArr[0].setOpaque(true);

		for (int i = 0; i < 6; i++) {
			lblCatArr[i].setBounds(0, scale(20 + (i * 35)), scale(149), scale(27));
			final int c = i;
			lblCatArr[i].addMouseListener(new MouseAdapter() {
				public void mouseReleased(MouseEvent e) {
					actionPerformed(new ActionEvent(lblCatArr[c], 0, ""));
				}
			});
			sp.add(lblCatArr[i]);
		}
		return sp;
	}

	private void toggleMonitoring(JLabel btn) {
		Config.getInstance().enableMonitoring(!Config.getInstance().isBrowserMonitoringEnabled());
		btn.setIcon(Config.getInstance().isBrowserMonitoringEnabled() ? ImageResource.get("on.png")
				: ImageResource.get("off.png"));
	}

	private void createMainMenu() {
		JMenuBar bar = new JMenuBar();
		bar.setBorderPainted(false);
		bar.setForeground(ColorResource.getWhite());
		bar.setMaximumSize(new Dimension(bar.getMaximumSize().width, XDMUtils.getScaledInt(30)));
		bar.setBackground(ColorResource.getTitleColor());

		JMenu file = createMenu(StringResource.get("MENU_FILE"));

		addMenuItem("MENU_ADD_URL", file);
		addMenuItem("MENU_VIDEO_DWN", file);
		addMenuItem("MENU_CLIP_ADD_MENU", file);
		addMenuItem("MENU_BATCH_DOWNLOAD", file);
		addMenuItem("MENU_DELETE_DWN", file);
		addMenuItem("MENU_DELETE_COMPLETED", file);
		addMenuItem("MENU_EXPORT", file);
		addMenuItem("MENU_IMPORT", file);
		addMenuItem("MENU_EXIT", file);

		JMenu dwn = createMenu(StringResource.get("MENU_DOWNLOAD"));

		addMenuItem("MENU_PAUSE", dwn);
		addMenuItem("MENU_RESUME", dwn);
		addMenuItem("MENU_RESTART", dwn);
		addMenuItem("DESC_Q_TITLE", dwn);

		PopupMenuListener popupListener = new PopupMenuListener() {

			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				loadQueueMenu(startQMenu);
				loadQueueMenu(stopQMenu);
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		};

		startQMenu = addSubMenu("MENU_START_Q", dwn, popupListener);
		stopQMenu = addSubMenu("MENU_STOP_Q", dwn, popupListener);

		JMenu tools = createMenu(StringResource.get("MENU_TOOLS"));

		addMenuItem("MENU_OPTIONS", tools);
		addMenuItem("MENU_REFRESH_LINK", tools);
		addMenuItem("MENU_PROPERTIES", tools);
		// addMenuItem("MENU_FORCE_ASSEMBLE", tools);
		addMenuItem("MENU_SPEED_LIMITER", tools);
		addMenuItem("MENU_LANG", tools);
		addMenuItem("MENU_MEDIA_CONVERTER", tools);
		addMenuItem("LBL_OPTIMIZE_NETWORK", tools);
		addMenuItem("MENU_BROWSER_INT", tools);

		JMenu help = createMenu(StringResource.get("MENU_HELP"));
		addMenuItem("MENU_CONTENTS", help);
		addMenuItem("MENU_HOME_PAGE", help);
		addMenuItem("LBL_SUPPORT_PAGE", help);
		addMenuItem("LBL_REPORT_PROBLEM", help);
		addMenuItem("LBL_TRANSLATE", help);
		addMenuItem("MENU_UPDATE", help);
		addMenuItem("OPT_UPDATE_FFMPEG", help);
		addMenuItem("MENU_ABOUT", help);

		bar.add(file);
		bar.add(dwn);
		bar.add(tools);
		bar.add(help);

		Box menuBox = Box.createHorizontalBox();
		menuBox.add(Box.createHorizontalGlue());
		menuBox.add(bar);
		menuBox.add(Box.createHorizontalStrut(getScaledInt(30)));
		getTitlePanel().add(menuBox);
	}

	private JMenu createMenu(String title) {
		JMenu menu = new JMenu(title);
		// menu.setForeground(ColorResource.getDeepFontColor());
		menu.setFont(FontResource.getBoldFont());
		menu.setBorderPainted(false);
		menu.setBorder(new EmptyBorder(getScaledInt(5), getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		return menu;
	}

	private void addMenuItem(String id, JComponent menu) {
		JMenuItem mitem = new JMenuItem(StringResource.get(id));
		// mitem.setForeground(ColorResource.getLightFontColor());
		mitem.setName(id);
		mitem.setFont(FontResource.getNormalFont());
		mitem.addActionListener(this);
		menu.add(mitem);
	}

	private JMenu addSubMenu(String id, JMenu parentMenu, PopupMenuListener popupListener) {
		JMenu menu = new JMenu(StringResource.get(id));
		menu.setName(id);
		menu.setFont(FontResource.getNormalFont());
		// menu.setForeground(ColorResource.getLightFontColor());
		menu.addActionListener(this);
		// menu.setBackground(ColorResource.getDarkerBgColor());
		menu.setBorderPainted(false);
		menu.getPopupMenu().addPopupMenuListener(popupListener);
		parentMenu.add(menu);
		return menu;
	}

	private JLabel createCategoryLabel(String name) {
		JLabel lblCat = new JLabel(StringResource.get(name));
		lblCat.setName(name);
		lblCat.setFont(FontResource.getBigFont());
		lblCat.setForeground(Color.BLACK);
		lblCat.setBorder(new EmptyBorder(getScaledInt(5), getScaledInt(20), getScaledInt(5), getScaledInt(5)));
		return lblCat;
	}

	private CustomButton createDropdownBtn(String textKey) {
		CustomButton btn = new CustomButton(StringResource.get(textKey));
		btn.setBackground(ColorResource.getActiveTabColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setContentAreaFilled(false);
		btn.setIcon(ImageResource.get("down.png"));
		btn.setVerticalTextPosition(SwingConstants.CENTER);
		btn.setHorizontalTextPosition(SwingConstants.LEFT);
		btn.setFont(FontResource.getNormalFont());
		return btn;
	}

	private Component createSearchPane() {
		btnSort = createDropdownBtn("SORT_DATE_DESC");

		// btnSort = new CustomButton(StringResource.get("SORT_DATE_DESC"));
		// btnSort.setBackground(ColorResource.getActiveTabColor());
		// btnSort.setBorderPainted(false);
		// btnSort.setFocusPainted(false);
		// btnSort.setContentAreaFilled(false);
		// btnSort.setFont(FontResource.getNormalFont());

		btnQueue = createDropdownBtn("LBL_ALL_QUEUE");

		txtSearch = new JTextField();
		txtSearch.setBackground(Color.WHITE);
		txtSearch.setForeground(Color.BLACK);
		txtSearch.setBorder(null);
		txtSearch.setName("BTN_SEARCH");
		txtSearch.addActionListener(this);

		final CustomButton btnSearch = new CustomButton();
		btnSearch.setName("BTN_SEARCH");
		btnSearch.setRolloverBackground(Color.WHITE);
		btnSearch.setPressedBackground(Color.WHITE);
		btnSearch.addActionListener(this);
		btnSearch.setPreferredSize(new Dimension(XDMUtils.getScaledInt(20), XDMUtils.getScaledInt(20)));
		btnSearch.setBackground(Color.WHITE);
		btnSearch.setIcon(ImageResource.get("search.png"));
		btnSearch.setBorderPainted(false);
		btnSearch.setFocusPainted(false);

		txtSearch.addActionListener(this);

		Box b = Box.createHorizontalBox();
		b.setOpaque(true);
		b.setBackground(Color.WHITE);
		b.setPreferredSize(new Dimension(scale(130), scale(20)));
		b.setMaximumSize(new Dimension(scale(130), scale(20)));
		txtSearch.setPreferredSize(new Dimension(scale(70), scale(20)));
		txtSearch.setMaximumSize(new Dimension(txtSearch.getMaximumSize().width, scale(20)));
		b.add(txtSearch);
		b.add(btnSearch);
		b.setBorder(new LineBorder(Color.LIGHT_GRAY, 1));

		Box bp = Box.createHorizontalBox();
		bp.setOpaque(false);
		bp.setBorder(new EmptyBorder(scale(3), scale(3), scale(3), scale(10)));
		bp.add(Box.createHorizontalStrut(10));
		bp.add(btnQueue);
		bp.add(Box.createHorizontalGlue());
		bp.add(btnSort);
		bp.add(Box.createHorizontalStrut(10));
		bp.add(b);
		bp.add(Box.createHorizontalStrut(10));

		sortItems = new JMenuItem[6];

		sortItems[0] = new JMenuItem(StringResource.get("SORT_DATE"));
		sortItems[0].setName("0");
		sortItems[1] = new JMenuItem(StringResource.get("SORT_SIZE"));
		sortItems[1].setName("1");
		sortItems[2] = new JMenuItem(StringResource.get("SORT_NAME"));
		sortItems[2].setName("2");
		sortItems[3] = new JMenuItem(StringResource.get("SORT_TYPE"));
		sortItems[3].setName("3");

		sortItems[4] = new JMenuItem(StringResource.get("SORT_ASC"));
		sortItems[4].setName("4");
		sortItems[5] = new JMenuItem(StringResource.get("SORT_DESC"));
		sortItems[5].setName("5");

		final JPopupMenu popSort = new JPopupMenu();
		for (int i = 0; i < sortItems.length; i++) {
			popSort.add(sortItems[i]);
			if (i > 3) {
				sortItems[i].putClientProperty("bgColor", ColorResource.getDarkBgColor());
			}
			sortItems[i].addActionListener(this);
		}

		updateSortMenu();

		popSort.setInvoker(btnSort);

		btnSort.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				popSort.show(btnSort, 0, btnSort.getHeight());
			}
		});

		btnQueue.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				prepeareQueuePopupButton();
			}
		});

		return bp;
	}

	private void prepeareQueuePopupButton() {
		final JPopupMenu popQ = new JPopupMenu();
		ArrayList<DownloadQueue> qlist = QueueManager.getInstance().getQueueList();

		JMenuItem[] qItems = new JMenuItem[qlist.size() + 1];
		qItems[0] = new JMenuItem(StringResource.get("LBL_ALL_QUEUE"));
		qItems[0].setName("Q_VIEW:ALL");
		popQ.add(qItems[0]);
		qItems[0].addActionListener(this);
		int index = -1;
		for (int i = 0; i < qlist.size(); i++) {
			String qId = qlist.get(i).getQueueId();
			DownloadQueue q = qlist.get(i);

			qItems[i + 1] = new JMenuItem(q.getName() + (q.isRunning() ? "*" : ""));
			qItems[i + 1].setName("Q_VIEW:" + qId);
			qItems[i + 1].addActionListener(this);
			popQ.add(qItems[i + 1]);
			String selectedQ = Config.getInstance().getQueueIdFilter();
			if (index == -1) {
				if (selectedQ != null && selectedQ.equals(qId)) {
					index = i + 1;
				}
			}
		}

		if (index == -1) {
			index = 0;
		}

		qItems[index].setFont(FontResource.getBoldFont());
		qItems[index].setForeground(ColorResource.getLightFontColor());

		popQ.setInvoker(btnQueue);
		popQ.show(btnQueue, 0, btnQueue.getHeight());
	}

	private void updateSortMenu() {
		for (int i = 0; i < sortItems.length; i++) {
			if (i >= 0 && i <= 3) {
				if (i == Config.getInstance().getSortField()) {
					sortItems[i].setFont(FontResource.getBoldFont());
					sortItems[i].setForeground(ColorResource.getLightFontColor());// (FontResource.getBoldFont());
				} else {
					sortItems[i].setFont(FontResource.getNormalFont());
					sortItems[i].setForeground(ColorResource.getDeepFontColor());
				}
			}
		}

		sortItems[4]
				.setFont(Config.getInstance().getSortAsc() ? FontResource.getBoldFont() : FontResource.getNormalFont());
		sortItems[4].setForeground(Config.getInstance().getSortAsc() ? ColorResource.getLightFontColor()
				: ColorResource.getDeepFontColor());
		sortItems[5].setFont(
				(!Config.getInstance().getSortAsc()) ? FontResource.getBoldFont() : FontResource.getNormalFont());
		sortItems[5].setForeground((!Config.getInstance().getSortAsc()) ? ColorResource.getLightFontColor()
				: ColorResource.getDeepFontColor());

	}

	private void createTabs() {
		CustomButton btnAllTab = new CustomButton(StringResource.get("ALL_DOWNLOADS")),
				btnIncompleteTab = new CustomButton(StringResource.get("ALL_UNFINISHED")),
				btnCompletedTab = new CustomButton(StringResource.get("ALL_FINISHED"));

		btnTabArr = new CustomButton[3];
		btnTabArr[0] = btnAllTab;
		btnTabArr[0].setName("ALL_DOWNLOADS");
		btnTabArr[1] = btnIncompleteTab;
		btnTabArr[1].setName("ALL_UNFINISHED");
		btnTabArr[2] = btnCompletedTab;
		btnTabArr[2].setName("ALL_FINISHED");

		for (int i = 0; i < 3; i++) {
			btnTabArr[i].setFont(FontResource.getBigBoldFont());
			btnTabArr[i].setBorderPainted(false);
			btnTabArr[i].setFocusPainted(false);
			btnTabArr[i].addActionListener(this);
		}

		btnAllTab.setBackground(ColorResource.getActiveTabColor());
		btnAllTab.setForeground(ColorResource.getDarkBgColor());

		btnIncompleteTab.setBackground(ColorResource.getTitleColor());
		btnIncompleteTab.setForeground(ColorResource.getDeepFontColor());

		btnCompletedTab.setBackground(ColorResource.getTitleColor());
		btnCompletedTab.setForeground(ColorResource.getDeepFontColor());

		JPanel pp = new JPanel(new BorderLayout());
		pp.setOpaque(false);

		JPanel p = new JPanel(new GridLayout(1, 3, scale(5), 0));
		p.setOpaque(false);
		Dimension d = new Dimension(scale(380), scale(30));
		p.setPreferredSize(d);
		p.setMaximumSize(d);
		p.setMinimumSize(d);
		p.setBackground(Color.WHITE);
		p.add(btnAllTab);
		p.add(btnIncompleteTab);
		p.add(btnCompletedTab);
		pp.add(p, BorderLayout.EAST);

		getTitlePanel().add(pp, BorderLayout.SOUTH);
	}

	private void tabClicked(ActionEvent e) {
		for (int i = 0; i < 3; i++) {
			if (btnTabArr[i] == e.getSource()) {
				btnTabArr[i].setBackground(ColorResource.getActiveTabColor());
				btnTabArr[i].setForeground(ColorResource.getDarkBgColor());
			} else {
				btnTabArr[i].setBackground(ColorResource.getTitleColor());
				btnTabArr[i].setForeground(ColorResource.getDeepFontColor());
			}
		}
	}

	private void initWindow() {
		setIconImage(ImageResource.get("icon.png").getImage());
		JLabel lblTitle = new JLabel(StringResource.get("WINDOW_TITLE"));
		lblTitle.setBorder(new EmptyBorder(scale(20), scale(20), 0, 0));
		lblTitle.setFont(FontResource.getBiggestFont());
		lblTitle.setForeground(ColorResource.getWhite());
		getTitlePanel().add(lblTitle, BorderLayout.WEST);
		createTabs();
		createMainMenu();

		BarPanel bp = new BarPanel();
		bp.setLayout(new BorderLayout());
		bp.add(Box.createRigidArea(new Dimension(0, scale(30))));
		bp.add(createSearchPane(), BorderLayout.EAST);

		JPanel panCenter = new JPanel(new BorderLayout());
		panCenter.setBackground(Color.WHITE);
		panCenter.add(bp, BorderLayout.NORTH);

		JPanel pClient = new JPanel(new BorderLayout());
		pClient.add(panCenter);
		pClient.add(createSidePanel(), BorderLayout.WEST);

		toolbar = createToolbar();
		pClient.add(toolbar, BorderLayout.SOUTH);

		add(pClient);

		sortStatusText = new String[4][2];

		sortStatusText[0][0] = StringResource.get("SORT_DATE_DESC");
		sortStatusText[0][1] = StringResource.get("SORT_DATE_ASC");

		sortStatusText[1][0] = StringResource.get("SORT_SIZE_DESC");
		sortStatusText[1][1] = StringResource.get("SORT_SIZE_ASC");

		sortStatusText[2][0] = StringResource.get("SORT_NAME_DESC");
		sortStatusText[2][1] = StringResource.get("SORT_NAME_ASC");

		sortStatusText[3][0] = StringResource.get("SORT_TYPE_DESC");
		sortStatusText[3][1] = StringResource.get("SORT_TYPE_ASC");
		// test ui

		// setMenuActionListener(this);

		lv = new DownloadListView(panCenter);
		filter();

		createPopupMenu();

		ToolTipManager.sharedInstance().setInitialDelay(500);
	}

	private void setWindowSizeAndPosition() {
		if (Config.getInstance().getWidth() < 0 || Config.getInstance().getHeight() < 0)
			setSize(scale(750), scale(450));
		if (Config.getInstance().getX() < 0 || Config.getInstance().getY() < 0)
			setLocationRelativeTo(null);
	}

	private void stopQueue(String name) {
		String queueId = "";
		String[] arr = name.split(":");
		if (arr.length > 1) {
			queueId = arr[1].trim();
		}
		DownloadQueue q = XDMApp.getInstance().getQueueById(queueId);
		if (q != null) {
			q.stop();
		}
	}

	private void startQueue(String name) {
		String queueId = "";
		String[] arr = name.split(":");
		if (arr.length > 1) {
			queueId = arr[1].trim();
		}
		DownloadQueue q = XDMApp.getInstance().getQueueById(queueId);
		if (q != null) {
			q.start();
		}
	}

	private void loadQueueMenu(JMenu menu) {
		if (menu.getName().equals("MENU_START_Q")) {
			loadStartQueueMenu(menu);
		} else if (menu.getName().equals("MENU_STOP_Q")) {
			loadStopQueueMenu(menu);
		}
	}

	private void loadStopQueueMenu(JMenu menu) {
		menu.removeAll();
		ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
		for (int i = 0; i < queues.size(); i++) {
			DownloadQueue q = queues.get(i);
			if (q.isRunning()) {
				JMenuItem mitem = new JMenuItem(q.getName());
				mitem.setForeground(ColorResource.getLightFontColor());
				mitem.setName("STOP:" + q.getQueueId());
				mitem.addActionListener(this);
				menu.add(mitem);
			}
		}
	}

	private void loadStartQueueMenu(JMenu menu) {
		menu.removeAll();
		ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
		for (int i = 0; i < queues.size(); i++) {
			DownloadQueue q = queues.get(i);
			if (!q.isRunning()) {
				JMenuItem mitem = new JMenuItem(q.getName());
				mitem.setForeground(ColorResource.getLightFontColor());
				mitem.setName("START:" + q.getQueueId());
				mitem.addActionListener(this);
				menu.add(mitem);
			}
		}
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
		addMenuItem("DWN_PREVIEW", popupCtx);
		addMenuItem("LBL_SHOW_PROGRESS", popupCtx);
		addMenuItem("CTX_COPY_URL", popupCtx);
		addMenuItem("CTX_COPY_FILE", popupCtx);
		addMenuItem("OPT_CONVERT", popupCtx);
		// convertMenu = createMenu(StringResource.get("OPT_CONVERT"));
		// convertMenu.setBorder(new EmptyBorder(5, 10, 5, 5));
		// convertMenu.setFont(FontResource.getNormalFont());
		//
		// MediaFormat[] fmts = MediaFormats.getSupportedFormats();
		// for (int i = 1; i < fmts.length; i++) {
		// MediaFormat fmt = fmts[i];
		// JMenuItem mitem = new JMenuItem(fmt.toString());
		// mitem.setName("FORMAT=" + i);
		// mitem.addActionListener(this);
		// convertMenu.add(mitem);
		// }
		//
		// popupCtx.add(convertMenu);

		addMenuItem("MENU_PROPERTIES", popupCtx);
		popupCtx.setInvoker(lv.getTable());
		lv.getTable().addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					System.out.println("Opening file");
					openFile();
				}
			}

			@Override
			public void mouseReleased(MouseEvent me) {
				// JOptionPane.showMessageDialog(null,"Mouse clicked:
				// "+me.getButton()+" "+MouseEvent.BUTTON3);
				if (me.getButton() == MouseEvent.BUTTON3 || SwingUtilities.isRightMouseButton(me) || me.isPopupTrigger()
						|| XDMUtils.isMacPopupTrigger(me)) {
					Point p = me.getPoint();
					JTable tbl = lv.getTable();
					if (tbl.getRowCount() < 1)
						return;
					if (tbl.getSelectedRow() < 0) {
						int row = tbl.rowAtPoint(p);
						if (row >= 0) {
							tbl.setRowSelectionInterval(row, row);
						}
					}
					if (tbl.getSelectedRows().length > 0) {
						popupCtx.show(lv.getTable(), me.getX(), me.getY());
					}
					// int row = tbl.rowAtPoint(p);
					// if (row < 0) {
					// tbl.setRowSelectionInterval(row, row);
					// }
				}
			}
		});
	}

	private void showProperties() {
		String[] ids = lv.getSelectedIds();
		if (ids.length > 0) {
			String id = ids[0];
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (id != null) {
				PropertiesPage propPage = PropertiesPage.getPage(this);
				HttpMetadata md = HttpMetadata.load(id);
				HeaderCollection headers = md.getHeaders();
				String referer = "";
				StringBuilder cookies = new StringBuilder();
				Iterator<HttpHeader> cookieIt = headers.getAll();
				while (cookieIt.hasNext()) {
					HttpHeader header = cookieIt.next();
					if ("referer".equalsIgnoreCase(header.getName())) {
						referer = header.getValue();
					}
					if ("cookie".equalsIgnoreCase(header.getName())) {
						cookies.append(header.getValue() + "\n");
					}
				}
				String type = "HTTP";
				if (md instanceof DashMetadata) {
					type = "DASH";
				} else if (md instanceof HlsMetadata) {
					type = "HLS";
				} else if (md instanceof HdsMetadata) {
					type = "HDS";
				}

				propPage.setDetails(ent.getFile(), XDMApp.getInstance().getFolder(ent), ent.getSize(), md.getUrl(),
						referer, ent.getDateStr(), cookies.toString(), type);
				propPage.showPanel();
			}
		}
	}

	public void openRefreshPage() {
		String[] ids = lv.getSelectedIds();
		if (ids.length > 0) {
			String id = ids[0];
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return;
			}
			if (!(ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED)) {
				return;
			}
			try {
				HttpMetadata md = HttpMetadata.load(id);
				RefreshUrlPage rp = RefreshUrlPage.getPage(this);
				rp.setDetails(md);
				rp.showPanel();
			} catch (Exception e2) {
				Logger.log(e2);
			}
		}
	}

	public void openFile(String id) {
		DownloadEntry ent = XDMApp.getInstance().getEntry(id);
		if (ent != null) {
			if (ent.getState() == XDMConstants.FINISHED) {
				try {
					XDMUtils.openFile(ent.getFile(), XDMApp.getInstance().getFolder(ent));
				} catch (FileNotFoundException e) {
					Logger.log(e);
					MessageBox.show(this, StringResource.get("ERR_MSG_FILE_NOT_FOUND"),
							StringResource.get("ERR_MSG_FILE_NOT_FOUND_MSG"), MessageBox.OK, MessageBox.OK);
				} catch (Exception e) {
					Logger.log(e);
				}
			}
		}
	}

	public void openFolder(String id) {
		DownloadEntry ent = XDMApp.getInstance().getEntry(id);
		if (ent != null) {
			if (ent.getState() == XDMConstants.FINISHED) {
				try {
					XDMUtils.openFolder(ent.getFile(), XDMApp.getInstance().getFolder(ent));
				} catch (FileNotFoundException e) {
					Logger.log(e);
					MessageBox.show(this, StringResource.get("ERR_MSG_FILE_NOT_FOUND"),
							StringResource.get("ERR_MSG_FILE_NOT_FOUND_MSG"), MessageBox.OK, MessageBox.OK);
				} catch (Exception e) {
					Logger.log(e);
				}
			}
		}
	}

	private void showConversionWindow(String[] ids) {
		ArrayList<String> list = new ArrayList<>();

		for (String id : ids) {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent != null) {
				if (ent.getState() == XDMConstants.FINISHED) {
					File f = new File(XDMApp.getInstance().getFolder(ent), ent.getFile());
					if (f.exists()) {
						list.add(f.getAbsolutePath());
					}
				}
			}
		}
		if (list.size() > 0) {
			VideoConversionWnd cw = new VideoConversionWnd(list);
			cw.setVisible(true);
			cw.load();
		}
	}

	private void convert() {
		try {
			String[] ids = lv.getSelectedIds();
			if (ids.length > 0) {
				showConversionWindow(ids);
				return;
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	private void updateFFmpeg() {
		FFmpegDownloader fd = new FFmpegDownloader();
		fd.start();
	}

	private void changeFile(String id) {
		DownloadEntry ent = XDMApp.getInstance().getEntry(id);
		if (ent == null)
			return;
		if (ent.getState() == XDMConstants.FINISHED) {
			return;
		}
		JFileChooser jfc = new JFileChooser();
		jfc.setSelectedFile(
				new File(XDMApp.getInstance().getOutputFolder(id), XDMApp.getInstance().getOutputFile(id, false)));
		if (jfc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			File f = jfc.getSelectedFile();
			ent.setFolder(f.getParent());
			ent.setFile(f.getName());
			XDMApp.getInstance().fileNameChanged(id);
		}
	}

	private void showVideoDwnDlg() {
		MediaDownloaderWnd wnd = new MediaDownloaderWnd();
		wnd.setVisible(true);
	}

	private void showLanguageDlg() {
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

		if (JOptionPane.showOptionDialog(null, obj, StringResource.get("MSG_LANG1"), JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE, null, null, null) == JOptionPane.OK_OPTION) {
			index = cmbLang.getSelectedIndex();
			if (index != -1) {
				Config.getInstance().setLanguage(keyList.get(index));
			}
			String lang = langMap.getProperty(cmbLang.getSelectedItem() + "");
			if (lang != null)
				Config.getInstance().setLanguage(lang);
		}
	}

	public void showNotification() {
		int mode = XDMApp.getInstance().getNotification();
		if (mode == -1) {
			clearNotification();
			return;
		}
		if (updateNotifyPanel == null) {
			updateNotifyPanel = new UpdateNotifyPanel();
			toolbar.add(updateNotifyPanel, BorderLayout.SOUTH);
		}
		updateNotifyPanel.setDetails(mode);
		revalidate();
		repaint();
	}

	private void clearNotification() {
		if (updateNotifyPanel == null)
			return;
		updateNotifyPanel.setVisible(false);
		toolbar.remove(updateNotifyPanel);
		updateNotifyPanel = null;
		invalidate();
		repaint();
	}

	private void pauseDownloads() {
		String[] ids = lv.getSelectedIds();
		Set<String> queues = new HashSet<String>();
		for (int i = 0; i < ids.length; i++) {
			DownloadEntry ent = XDMApp.getInstance().getEntry(ids[i]);
			String qid = ent.getQueueId();
			queues.add(qid);
		}

		Iterator<String> qit = queues.iterator();
		boolean qRunning = false;
		while (qit.hasNext()) {
			String qid = qit.next();
			if (qid != null) {
				DownloadQueue q = XDMApp.getInstance().getQueueById(qid);
				if (q != null) {
					if (q.isRunning()) {
						qRunning = true;
						break;
					}
				}
			}
		}

		if (qRunning && (MessageBox.show(this, StringResource.get("MSG_REF_LINK_CONFIRM"),
				StringResource.get("LBL_STOP_Q"), MessageBox.YES_NO_OPTION, MessageBox.YES) == MessageBox.YES)) {
			qit = queues.iterator();
			while (qit.hasNext()) {
				String qid = qit.next();
				if (qid != null) {
					DownloadQueue q = XDMApp.getInstance().getQueueById(qid);
					if (q != null) {
						if (q.isRunning()) {
							q.stop();
						}
					}
				}
			}
		} else {
			for (int i = 0; i < ids.length; i++) {
				XDMApp.getInstance().pauseDownload(ids[i]);
			}
		}
	}

	private void openFile() {
		String[] ids = lv.getSelectedIds();
		if (ids.length > 0) {
			String id = ids[0];
			openFile(id);
		}
	}

	private void showBatchDialog() {
		List<String> urlList = BatchDownloadWnd.getUrls();
		if (urlList.size() > 0) {
			new BatchDownloadWnd(XDMUtils.toMetadata(urlList)).setVisible(true);
		} else {
			MessageBox.show(this, StringResource.get("MENU_BATCH_DOWNLOAD"),
					StringResource.get("LBL_BATCH_EMPTY_CLIPBOARD"), MessageBox.OK_OPTION, MessageBox.OK);
		}
	}

	private void optimizeRWin() {
		JComboBox<String> cmbLang = new JComboBox<>(
				new String[] { StringResource.get("LBL_NET_OPT_DEF"), StringResource.get("LBL_NET_OPT_64"),
						StringResource.get("LBL_NET_OPT_128"), StringResource.get("LBL_NET_OPT_256") });
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

		if (JOptionPane.showOptionDialog(null, obj, StringResource.get("LBL_OPTIMIZE_NETWORK"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, null, null) == JOptionPane.OK_OPTION) {
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

	private void openTranslationPage() {
		XDMUtils.browseURL("https://sourceforge.net/p/xdman/discussion/translate/");
	}

	private void openSupportPage() {
		XDMUtils.browseURL("https://sourceforge.net/p/xdman/discussion/xdmhelp/");
	}

	private void openBugReportPage() {
		XDMUtils.browseURL("https://sourceforge.net/p/xdman/discussion/xdmbug/");
	}

	private int scale(int i) {
		return XDMUtils.getScaledInt(i);
	}

	private void showBatchPatternDialog() {
		new BatchPatternDialog().setVisible(true);
	}

	// void test() {
	// HttpMetadata m = new HttpMetadata();
	// m.setUrl("http://localhost:8080/x.zip");
	// HttpDownloader hd = new HttpDownloader(UUID.randomUUID().toString(),
	// "c:\\users\\subhro\\desktop\\temp", m,
	// new TestProxyResolver());
	// hd.registerListener(XDMApp.getInstance());
	// hd.start();
	// }

}