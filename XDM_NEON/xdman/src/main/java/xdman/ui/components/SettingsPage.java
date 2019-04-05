package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.font.TextAttribute;
import java.io.File;
import java.net.PasswordAuthentication;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.MouseInputAdapter;

import xdman.ClipboardMonitor;
import xdman.Config;
import xdman.CredentialManager;
import xdman.DownloadEntry;
import xdman.DownloadQueue;
import xdman.QueueManager;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.BrowserLauncher;
import xdman.util.DateTimeUtils;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class SettingsPage extends JPanel implements ActionListener, ListSelectionListener {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2125547008738968050L;
	private static SettingsPage page;
	Color bgColor;
	JScrollPane jsp;
	XDMFrame parent;
	int diffx, diffy;
	int level;

	JPanel overviewPanel;
	JPanel browserIntPanel;
	JPanel networkPanel;
	JPanel passwordPanel;
	JPanel queuePanel;
	JPanel advPanel;

	JLabel titleLbl;
	JLabel btnNav;

	JList<PasswordItem> passList;
	DefaultListModel<PasswordItem> passListModel;
	JTextField txtCredHostName;

	ArrayList<JPanel> pageStack;

	JList<DownloadQueue> qList;
	JList<String> qItemsList;
	JTextField txtQueueName;
	JCheckBox chkQStart, chkQStop;
	JRadioButton radOnetime, radPeriodic;
	JCheckBox[] chkDays;
	JSpinner spExecDate, spEndTime, spStartTime;
	JButton btnQMoveTo;

	int[] sizeArr = { 0, 512 * 1024, 1024 * 1024, 5 * 1024 * 1024, 10 * 1024 * 1024 };

	SpinnerDateModel spinnerDateModel1, spinnerDateModel2, spinnerDateModel3;

	DefaultListModel<DownloadQueue> queueModel;

	DefaultListModel<String> queuedItemsModel;

	JTextField txtUserName, txtPassword;

	JPanel currentPage;

	JCheckBox chkPrgWnd;
	JCheckBox chkEndWnd;
	JCheckBox chkVidPan;
	JCheckBox chkOverwriteExisting;
	JComboBox<String> cmbMax;
	JComboBox<String> cmbMinVidSize;
	// JComboBox<String> cmbDupAction;
	JTextField txtDefFolder, txtTempFolder;

	JTextArea txtFileTyp, txtVidType, txtBlockedHosts;

	JComboBox<String> cmbTimeout, cmbSeg, cmbTcp;
	// JTextField txtSpeedLimit;
	JCheckBox chkUsePac, chkUseProxy, chkUseSocks;
	JTextField txtPACUrl, txtProxyHostnPort, txtProxyPass, txtProxyUser, txtSocksHostnPort;

	JCheckBox chkHaltAfterFinish, chkKeepAwake, chkExecCmd, chkExecAntivir, chkAutoStart, chkMonitorClipboard,
			chkDwnAuto, chkGetTs, chkNoTransparency, chkForceFolder, chkShowTray, chkVidBrowserOnly;

	JTextField txtCustomCmd, txtAntivirCmd, txtAntivirArgs;

	JComboBox<String> cmbCategory;

	private static final String chromeWebStoreURL = "https://chrome.google.com/webstore/detail/xdm-browser-monitor/bgpkelneombgembocnickiddlbebmica",
			ffAMOURL = "https://subhra74.github.io/xdm-firefox/firefox.html", // "http://xdman.sourceforge.net/addons/xdm_ff_webext.xpi",
			operaExtURL = "https://subhra74.github.io/xdm-firefox/chromium.html",
			directCRXURL = "https://subhra74.github.io/xdm-firefox/chromium.html";
	// https://subhra74.github.io/xdm-firefox/xdm_ff_webext.xpi

	public static SettingsPage getInstance() {
		if (page == null) {
			page = new SettingsPage();
		}
		return page;
	}

	public SettingsPage() {
		setOpaque(false);
		setLayout(null);
		bgColor = new Color(0, 0, 0, Config.getInstance().isNoTransparency() ? 255 : 200);
		MouseInputAdapter ma = new MouseInputAdapter() {
		};

		addMouseListener(ma);
		addMouseMotionListener(ma);

		jsp = new JScrollPane();
		jsp.setOpaque(false);
		jsp.setBorder(null);
		jsp.getViewport().setOpaque(false);

		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		jsp.getVerticalScrollBar().setUnitIncrement(getScaledInt(10));
		jsp.getVerticalScrollBar().setBlockIncrement(getScaledInt(25));

		add(jsp);

		registerMouseListener();

		init();

		pageStack = new ArrayList<JPanel>();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(bgColor);
		g.fillRect(0, 0, getWidth(), getHeight());
	}

	public void showPanel(XDMFrame xframe, String pageName) {
		this.parent = xframe;
		int x = xframe.getWidth() - getScaledInt(350);
		jsp.setBounds(0, y, getScaledInt(350), xframe.getHeight() - y);
		setBounds(x, 0, getScaledInt(350), xframe.getHeight());
		JScrollBar vertical = jsp.getVerticalScrollBar();
		vertical.setValue(vertical.getMinimum());
		setPage(pageName);
		xframe.showDialog(this);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				jsp.getVerticalScrollBar().setValue(0);
			}
		});
	}

	public void registerMouseListener() {
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				diffx = me.getXOnScreen() - parent.getLocationOnScreen().x;
				diffy = me.getYOnScreen() - parent.getLocationOnScreen().y;
			}
		});

		addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent me) {
				parent.setLocation(me.getXOnScreen() - diffx, me.getYOnScreen() - diffy);
			}
		});
	}

	int y = 0;
	int h = 0;

	private void init() {
		y = getScaledInt(25);
		h = getScaledInt(40);

		btnNav = new JLabel(ImageResource.get("back.png"));
		btnNav.setFont(FontResource.getBiggerFont());
		btnNav.setForeground(ColorResource.getSelectionColor());
		btnNav.setBounds(getScaledInt(15), y, getScaledInt(35), h);
		add(btnNav);

		btnNav.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				if (currentPage == overviewPanel) {
					saveOverviewSettings();
				} else if (currentPage == browserIntPanel) {
					saveMonitoringSettings();
				} else if (currentPage == networkPanel) {
					saveNetworkSettings();
				} else if (currentPage == advPanel) {
					saveAdvSettings();
				}
				if (level == 0) {
					close();
				}
				if (level == 1) {
					level = 0;
					setPage("PG_SETTINGS");
				}
			}
		});

		titleLbl = new JLabel(StringResource.get("TITLE_SETTINGS"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(50), y, getScaledInt(200), h);
		add(titleLbl);

		y += h;
		y += getScaledInt(10);
		h = getScaledInt(2);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, y, getScaledInt(400), h);
		lineLbl.setOpaque(true);
		add(lineLbl);

		y += h;

	}

	private JPanel createOverviewPanel() {
		JPanel panel = new JPanel();
		panel.setOpaque(false);
		panel.setLayout(null);

		int y = 0, h = 0;
		y += getScaledInt(10);

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblMonitorHeader = new JLabel(StringResource.get("SETTINGS_MONITORING"));
		lblMonitorHeader.setForeground(Color.WHITE);
		lblMonitorHeader.setFont(FontResource.getItemFont());
		lblMonitorHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblMonitorHeader);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(50);
		JTextArea lblMonitoringTitle = new JTextArea();
		lblMonitoringTitle.setOpaque(false);
		lblMonitoringTitle.setWrapStyleWord(true);
		lblMonitoringTitle.setLineWrap(true);
		lblMonitoringTitle.setEditable(false);
		lblMonitoringTitle.setForeground(Color.WHITE);
		lblMonitoringTitle.setText(StringResource.get("HINT_BROWSER_MON"));
		lblMonitoringTitle.setFont(FontResource.getNormalFont());
		lblMonitoringTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblMonitoringTitle);
		y += h;

		JButton btViewMonitoring = createButton1("SETTINGS_VIEW", getScaledInt(15), y);
		btViewMonitoring.setName("BTN_MONITORING");
		panel.add(btViewMonitoring);
		y += btViewMonitoring.getHeight();

		y += getScaledInt(10);
		y += getScaledInt(20);

		h = getScaledInt(30);
		JLabel lblGenHeader = new JLabel(StringResource.get("SETTINGS_GENERAL"));
		lblGenHeader.setForeground(Color.WHITE);
		lblGenHeader.setFont(FontResource.getItemFont());
		lblGenHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblGenHeader);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(30);
		chkPrgWnd = createCheckBox("SHOW_DWN_PRG");
		chkPrgWnd.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(chkPrgWnd);
		y += h;

		h = getScaledInt(30);
		chkEndWnd = createCheckBox("SHOW_DWN_COMPLETE");
		chkEndWnd.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(chkEndWnd);
		y += h;

		h = getScaledInt(30);
		chkOverwriteExisting = createCheckBox("LBL_OVERWRITE_EXISTING");
		chkOverwriteExisting.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(chkOverwriteExisting);
		y += h;

		h = getScaledInt(30);
		chkNoTransparency = createCheckBox("LBL_TRANSPARENCY");
		chkNoTransparency.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(chkNoTransparency);
		y += h;

		h = getScaledInt(30);
		chkVidBrowserOnly = createCheckBox("LBL_SHOW_VIDEO_ONLY_IN_BROWSER");
		chkVidBrowserOnly.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(chkVidBrowserOnly);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblMaxTitle = new JLabel(StringResource.get("MSG_MAX_DOWNLOAD"));
		lblMaxTitle.setForeground(Color.WHITE);
		lblMaxTitle.setFont(FontResource.getNormalFont());
		lblMaxTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblMaxTitle);
		y += getScaledInt(3);

		h = getScaledInt(25);
		cmbMax = new JComboBox<String>(new String[] { "1", "2", "5", "10", "50", "100", "N/A" });
		cmbMax.setBackground(ColorResource.getDarkerBgColor());
		cmbMax.setBounds(getScaledInt(250), y, getScaledInt(75), h);
		cmbMax.setRenderer(new SimpleListRenderer());
		panel.add(cmbMax);
		y += h;
		y += getScaledInt(10);

		// h = 30;
		// JLabel lblDupTitle = new JLabel(StringResource.get("SHOW_DUP_ACT"));
		// lblDupTitle.setForeground(Color.WHITE);
		// lblDupTitle.setFont(FontResource.getNormalFont());
		// lblDupTitle.setBounds(15, y, 350 - 30, h);
		// panel.add(lblDupTitle);
		// y += h;
		//
		// h = 25;
		// cmbDupAction = new JComboBox<String>(
		// new String[] { StringResource.get("DUT_ACT_RENAME"),
		// StringResource.get("DUP_ACT_OVERWRITE"),
		// StringResource.get("DUP_ACT_OPEN"),
		// StringResource.get("DUP_ACT_PROMPT") });
		// cmbDupAction.setBackground(ColorResource.getDarkerBgColor());
		// cmbDupAction.setOpaque(false);
		// cmbDupAction.setBounds(15, y, 350 - 40, h);
		// cmbDupAction.setRenderer(new SimpleListRenderer());
		// panel.add(cmbDupAction);
		// y += h;

		y += getScaledInt(10);

		h = getScaledInt(30);
		chkForceFolder = createCheckBox("LBL_FORCE_FOLDER");
		chkForceFolder.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		chkForceFolder.addActionListener(this);
		panel.add(chkForceFolder);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblFolderTitle = new JLabel(StringResource.get("SETTINGS_FOLDER"));
		lblFolderTitle.setForeground(Color.WHITE);
		lblFolderTitle.setFont(FontResource.getNormalFont());
		lblFolderTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblFolderTitle);
		y += h;

		h = getScaledInt(25);
		cmbCategory = new JComboBox<>(
				new String[] { StringResource.get("LBL_GENERAL_CAT"), StringResource.get("CAT_DOCUMENTS"),
						StringResource.get("CAT_COMPRESSED"), StringResource.get("CAT_MUSIC"),
						StringResource.get("CAT_VIDEOS"), StringResource.get("CAT_PROGRAMS") });
		cmbCategory.setName("CMB_CATEGORY");
		cmbCategory.setBackground(ColorResource.getDarkerBgColor());
		cmbCategory.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		cmbCategory.setRenderer(new SimpleListRenderer());
		cmbCategory.addActionListener(this);
		panel.add(cmbCategory);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(25);
		txtDefFolder = new JTextField();
		txtDefFolder.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(110), h);
		txtDefFolder.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtDefFolder.setEditable(false);
		txtDefFolder.setForeground(Color.WHITE);
		txtDefFolder.setOpaque(false);
		panel.add(txtDefFolder);
		JButton btnBrowseFolder = createButton2("SETTINGS_FOLDER_CHANGE");
		btnBrowseFolder.setName("SETTINGS_FOLDER_CHANGE");
		btnBrowseFolder.setBackground(ColorResource.getDarkBtnColor());
		btnBrowseFolder.setFont(FontResource.getNormalFont());
		btnBrowseFolder.setBounds(
				getScaledInt(15) + getScaledInt(350) - getScaledInt(30) - getScaledInt(110) + getScaledInt(10), y,
				getScaledInt(90), h);
		panel.add(btnBrowseFolder);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblTempFolderTitle = new JLabel(StringResource.get("LBL_TEMP_FOLDER"));
		lblTempFolderTitle.setForeground(Color.WHITE);
		lblTempFolderTitle.setFont(FontResource.getNormalFont());
		lblTempFolderTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblTempFolderTitle);
		y += h;

		h = getScaledInt(25);
		txtTempFolder = new JTextField();
		txtTempFolder.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(110), h);
		txtTempFolder.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtTempFolder.setEditable(false);
		txtTempFolder.setForeground(Color.WHITE);
		txtTempFolder.setOpaque(false);
		panel.add(txtTempFolder);
		JButton btnBrowseFolder2 = createButton2("SETTINGS_FOLDER_CHANGE");
		btnBrowseFolder2.setName("SETTINGS_TEMP_FOLDER_CHANGE");
		btnBrowseFolder2.setBackground(ColorResource.getDarkBtnColor());
		btnBrowseFolder2.setFont(FontResource.getNormalFont());
		btnBrowseFolder2.setBounds(
				getScaledInt(15) + getScaledInt(350) - getScaledInt(30) - getScaledInt(110) + getScaledInt(10), y,
				getScaledInt(90), h);
		panel.add(btnBrowseFolder2);
		y += h;

		y += getScaledInt(10);
		y += getScaledInt(20);

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblNetHeader = new JLabel(StringResource.get("SETTINGS_NETWORK"));
		lblNetHeader.setForeground(Color.WHITE);
		lblNetHeader.setFont(FontResource.getItemFont());
		lblNetHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblNetHeader);
		y += h;

		h = getScaledInt(40);
		JTextArea lblNetworkTitle = new JTextArea();
		lblNetworkTitle.setOpaque(false);
		lblNetworkTitle.setWrapStyleWord(true);
		lblNetworkTitle.setLineWrap(true);
		lblNetworkTitle.setEditable(false);
		lblNetworkTitle.setForeground(Color.WHITE);
		lblNetworkTitle.setText(StringResource.get("HINT_NETWORK"));
		lblNetworkTitle.setFont(FontResource.getNormalFont());
		lblNetworkTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblNetworkTitle);
		y += h;

		JButton btViewNet = createButton1("SETTINGS_VIEW", getScaledInt(15), y);
		btViewNet.setName("BTN_NETWORK");
		panel.add(btViewNet);
		y += btViewNet.getHeight();

		y += getScaledInt(10);
		y += getScaledInt(10);

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblSchHeader = new JLabel(StringResource.get("SETTINGS_SCHEDULER"));
		lblSchHeader.setForeground(Color.WHITE);
		lblSchHeader.setFont(FontResource.getItemFont());
		lblSchHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblSchHeader);
		y += h;

		h = getScaledInt(50);
		JTextArea lblScheduleTitle = new JTextArea();
		lblScheduleTitle.setOpaque(false);
		lblScheduleTitle.setWrapStyleWord(true);
		lblScheduleTitle.setLineWrap(true);
		lblScheduleTitle.setEditable(false);
		lblScheduleTitle.setForeground(Color.WHITE);
		lblScheduleTitle.setText(StringResource.get("HINT_SCHEDULER"));
		lblScheduleTitle.setFont(FontResource.getNormalFont());
		lblScheduleTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblScheduleTitle);
		y += h;

		JButton btViewScheduler = createButton1("SETTINGS_VIEW", getScaledInt(15), y);
		btViewScheduler.setName("Q_MAN");
		panel.add(btViewScheduler);
		y += btViewScheduler.getHeight();

		y += getScaledInt(10);
		y += getScaledInt(10);

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblPwdHeader = new JLabel(StringResource.get("SETTINGS_CRED"));
		lblPwdHeader.setForeground(Color.WHITE);
		lblPwdHeader.setFont(FontResource.getItemFont());
		lblPwdHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblPwdHeader);
		y += h;

		h = getScaledInt(40);
		JTextArea lblCredTitle = new JTextArea();
		lblCredTitle.setOpaque(false);
		lblCredTitle.setWrapStyleWord(true);
		lblCredTitle.setLineWrap(true);
		lblCredTitle.setEditable(false);
		lblCredTitle.setForeground(Color.WHITE);
		lblCredTitle.setText(StringResource.get("HINT_PASSWORD"));
		lblCredTitle.setFont(FontResource.getNormalFont());
		lblCredTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblCredTitle);
		y += h;

		JButton btViewCred = createButton1("SETTINGS_VIEW", getScaledInt(15), y);
		btViewCred.setName("PASS_MAN");
		panel.add(btViewCred);
		y += btViewCred.getHeight();

		y += getScaledInt(10);
		y += getScaledInt(10);

		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lblAdvHeader = new JLabel(StringResource.get("SETTINGS_ADV"));
		lblAdvHeader.setForeground(Color.WHITE);
		lblAdvHeader.setFont(FontResource.getItemFont());
		lblAdvHeader.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblAdvHeader);
		y += h;

		h = getScaledInt(50);
		JTextArea lblAdvTitle = new JTextArea();
		lblAdvTitle.setOpaque(false);
		lblAdvTitle.setWrapStyleWord(true);
		lblAdvTitle.setLineWrap(true);
		lblAdvTitle.setEditable(false);
		lblAdvTitle.setForeground(Color.WHITE);
		lblAdvTitle.setText(StringResource.get("HINT_ADV"));
		lblAdvTitle.setFont(FontResource.getNormalFont());
		lblAdvTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblAdvTitle);
		y += h;

		JButton btViewAdv = createButton1("SETTINGS_VIEW", getScaledInt(15), y);
		btViewAdv.setName("ADV_MAN");
		panel.add(btViewAdv);
		y += btViewAdv.getHeight();

		y += getScaledInt(10);

		panel.setPreferredSize(new Dimension(getScaledInt(300), y + getScaledInt(50)));
		// panel.setMinimumSize(new Dimension(300, 700));
		return panel;
	}

	private JButton createButton1(String name, int x, int y) {
		JButton btn = new CustomButton(StringResource.get(name));
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		Dimension d = btn.getPreferredSize();
		btn.setBounds(x, y, d.width, d.height);
		btn.addActionListener(this);
		return btn;
	}

	private JButton createButton2(String name) {
		JButton btn = new CustomButton(StringResource.get(name));
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		btn.addActionListener(this);
		return btn;
	}

	private JCheckBox createCheckBox(String name, Font font) {
		JCheckBox chk = new JCheckBox(StringResource.get(name));
		chk.setName(name);
		chk.setIcon(ImageResource.get("unchecked.png"));
		chk.setSelectedIcon(ImageResource.get("checked.png"));
		chk.setOpaque(false);
		chk.setFocusPainted(false);
		chk.setForeground(Color.WHITE);
		chk.setFont(font);
		return chk;
	}

	private JCheckBox createCheckBox(String name) {
		return createCheckBox(name, FontResource.getNormalFont());
	}

	private void close() {
		parent.hideDialog(this);
		System.gc();
	}

	private void showMsgFF() {
		String msg = String.format(StringResource.get("MSG_GENERIC_ADDON_DESC"), "", ffAMOURL);
		MessageBox.show(parent, StringResource.get("MSG_ADDON_TITLE"), msg, MessageBox.OK_OPTION, MessageBox.OK);
	}

	private void showMsgChrome() {
		String msg = String.format(StringResource.get("MSG_GENERIC_ADDON_DESC"), chromeWebStoreURL, directCRXURL);
		MessageBox.show(parent, StringResource.get("MSG_ADDON_TITLE"), msg, MessageBox.OK_OPTION, MessageBox.OK);
	}

	private void showAddonUrl(String url, String desc) {
		BrowserAddonDlg dlg = new BrowserAddonDlg(url, desc);
		dlg.setVisible(true);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JRadioButton) {
			JRadioButton chk = (JRadioButton) e.getSource();
			if ("Q_DAILY".equals(chk.getName()) || "Q_ONCE".equals(chk.getName())) {
				enableSchedulerFields();
			}
		}
		if (e.getSource() instanceof JMenuItem) {
			String name = ((JMenuItem) e.getSource()).getName();
			if (name != null) {
				if (name.startsWith("Q_MOVE_TO:")) {
					String targetQ = "";
					if (name.endsWith(":")) {
						targetQ = "";
					} else {
						targetQ = name.split(":")[1].trim();
					}
					int index = qItemsList.getSelectedIndex();
					if (index < 0)
						return;
					String id = queuedItemsModel.get(index);
					Logger.log("Moving to target queue: " + targetQ);
					index = qList.getSelectedIndex();
					if (index < 0)
						return;
					DownloadQueue sourceQ = queueModel.getElementAt(index);
					sourceQ.removeFromQueue(id);
					QueueManager.getInstance().getQueueById(targetQ).addToQueue(id);
					loadSchedulerSettings(index);
				}
				return;
			}
		}
		if (e.getSource() == cmbCategory) {
			int index = cmbCategory.getSelectedIndex();
			Logger.log("Category changed");
			switch (index) {
			case 0:
				txtDefFolder.setText(Config.getInstance().getCategoryOther());
				break;
			case 1:
				txtDefFolder.setText(Config.getInstance().getCategoryDocuments());
				break;
			case 2:
				txtDefFolder.setText(Config.getInstance().getCategoryCompressed());
				break;
			case 3:
				txtDefFolder.setText(Config.getInstance().getCategoryMusic());
				break;
			case 4:
				txtDefFolder.setText(Config.getInstance().getCategoryVideos());
				break;
			case 5:
				txtDefFolder.setText(Config.getInstance().getCategoryPrograms());
				break;
			}
		}

		if (e.getSource() instanceof JCheckBox) {
			JCheckBox chk = (JCheckBox) e.getSource();
			if ("MSG_Q_START".equals(chk.getName())) {
				enableSchedulerFields();
			} else if ("LBL_FORCE_FOLDER".equals(chk.getName())) {
				Logger.log("Checked");
				if (chkForceFolder.isSelected()) {
					cmbCategory.setSelectedIndex(0);
					cmbCategory.setEnabled(false);
					Config.getInstance().setDownloadFolder(txtDefFolder.getText());
					Config.getInstance().setForceSingleFolder(true);
				} else {
					cmbCategory.setEnabled(true);
					Config.getInstance().setForceSingleFolder(false);
					Config.getInstance().createFolders();
					cmbCategory.setSelectedIndex(0);
				}
			}
		} else if (e.getSource() instanceof JButton) {
			JButton btn = (JButton) e.getSource();
			String name = btn.getName();
			if ("BTN_Q_SAVE".equals(name)) {
				saveQueue();
				return;
			}
			if ("Q_MOVE_TO".equals(name)) {
				showMoveQPopup(btn);
				return;
			}
			if ("BTN_Q_DEL".equals(name)) {
				removeQueue();
			}
			if ("Q_MOVE_UP".equals(name)) {
				queueMoveUp();
				return;
			}
			if ("Q_MOVE_DN".equals(name)) {
				queueMoveDown();
				return;
			}
			if ("BTN_Q_NEW".equals(name)) {
				createNewQueue();
				return;
			}
			if ("BTN_DEF_FILE_EXT".equals(name)) {
				txtFileTyp.setText(XDMUtils.appendArray2Str(Config.getInstance().getDefaultFileTypes()));
				return;
			}
			if ("BTN_DEF_VID_EXT".equals(name)) {
				txtVidType.setText(XDMUtils.appendArray2Str(Config.getInstance().getDefaultVideoTypes()));
				return;
			}
			if ("BTN_Q_NEW".equals(name)) {
				createNewQueue();
				return;
			}
			if ("DEL_CRED".equals(name)) {
				int index = passList.getSelectedIndex();
				if (index > -1) {
					PasswordItem item = passListModel.get(index);
					CredentialManager.getInstance().removeSavedCredential(item.host);
					loadPasswordSettings();
				}
			}
			if ("NEW_CRED".equals(name)) {
				txtCredHostName.setText("");
				txtUserName.setText("");
				txtPassword.setText("");
			}
			if ("SAVE_CRED".equals(name)) {
				savePasswordSettings();
			}
			if ("BROWSE_ANTIVIR".equals(name)) {
				JFileChooser jfc = new JFileChooser();
				if (jfc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
					txtAntivirCmd.setText(jfc.getSelectedFile().getAbsolutePath());
				}
			}
			if ("SETTINGS_FOLDER_CHANGE".equals(name)) {
				JFileChooser jfc = new JFileChooser();
				String folderText = txtDefFolder.getText().trim();
				File file = new File(
						StringUtils.isNullOrEmptyOrBlank(folderText) ? Config.getInstance().getCategoryOther()
								: folderText);
				if (file.exists()) {
					jfc.setCurrentDirectory(file);
				}
				jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				if (jfc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
					File folder = jfc.getSelectedFile();
					if (folder.exists()) {
						txtDefFolder.setText(folder.getAbsolutePath());
						if (Config.getInstance().isForceSingleFolder()) {
							Config.getInstance().setDownloadFolder(txtDefFolder.getText());
							return;
						}
						int index = cmbCategory.getSelectedIndex();
						switch (index) {
						case 0:
							Config.getInstance().setCategoryOther(txtDefFolder.getText());
							break;
						case 1:
							Config.getInstance().setCategoryDocuments(txtDefFolder.getText());
							break;
						case 2:
							Config.getInstance().setCategoryCompressed(txtDefFolder.getText());
							break;
						case 3:
							Config.getInstance().setCategoryMusic(txtDefFolder.getText());
							break;
						case 4:
							Config.getInstance().setCategoryVideos(txtDefFolder.getText());
							break;
						case 5:
							Config.getInstance().setCategoryPrograms(txtDefFolder.getText());
							break;
						}
					}
				}
			}
			if ("SETTINGS_TEMP_FOLDER_CHANGE".equals(name)) {
				JFileChooser jfc = new JFileChooser();
				String folderText = txtTempFolder.getText().trim();
				File file = new File(
						StringUtils.isNullOrEmptyOrBlank(folderText) ? Config.getInstance().getCategoryOther()
								: folderText);
				if (file.exists()) {
					jfc.setCurrentDirectory(file);
				}
				jfc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				if (jfc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
					File folder = jfc.getSelectedFile();
					if (folder.exists()) {
						txtTempFolder.setText(folder.getAbsolutePath());
					}
				}
			}
			if ("FF_INSTALL".equals(name)) {
				if (!BrowserLauncher.launchFirefox(ffAMOURL)) {
					String msg = String.format(StringResource.get("MSG_ADDON_DESC"), "Mozilla Firefox", ffAMOURL);
					showAddonUrl(ffAMOURL, msg);
					// BrowserAddonDlg dlg = new BrowserAddonDlg(ffAMOURL, msg);
					// dlg.setVisible(true);
					// MessageBox.show(parent,
					// StringResource.get("MSG_ADDON_TITLE"), msg,
					// MessageBox.OK_OPTION,
					// MessageBox.OK);
				}
			}
			if ("EDGE_INSTALL".equals(name)) {
				XDMUtils.browseURL("https://sourceforge.net/p/xdman/blog/2018/01/xdm-integration-with-microsoft-edge/");
			}
			if ("CR_INSTALL".equals(name)) {
				if (!BrowserLauncher.launchChrome(chromeWebStoreURL)) {
					String msg = String.format(StringResource.get("MSG_ADDON_DESC"), "Google Chrome",
							chromeWebStoreURL);
					showAddonUrl(chromeWebStoreURL, msg);
					// MessageBox.show(parent,
					// StringResource.get("MSG_ADDON_TITLE"), msg,
					// MessageBox.OK_OPTION,
					// MessageBox.OK);
				}
			}
			if ("CM_INSTALL".equals(name)) {
				String msg = String.format(StringResource.get("MSG_ADDON_DESC"), "Chromium", chromeWebStoreURL);
				showAddonUrl(chromeWebStoreURL, msg);
				// MessageBox.show(parent,
				// StringResource.get("MSG_ADDON_TITLE"), msg,
				// MessageBox.OK_OPTION,
				// MessageBox.OK);
			}
			if ("VL_INSTALL".equals(name)) {
				String msg = String.format(StringResource.get("MSG_ADDON_DESC"), "Vivaldi", chromeWebStoreURL);
				showAddonUrl(chromeWebStoreURL, msg);
				// MessageBox.show(parent,
				// StringResource.get("MSG_ADDON_TITLE"), msg,
				// MessageBox.OK_OPTION,
				// MessageBox.OK);
			}
			if ("OP_INSTALL".equals(name)) {
				String msg = String.format(StringResource.get("MSG_ADDON_DESC"), "Opera", operaExtURL);
				showAddonUrl(operaExtURL, msg);
				// MessageBox.show(parent,
				// StringResource.get("MSG_ADDON_TITLE"), msg,
				// MessageBox.OK_OPTION,
				// MessageBox.OK);
			}
			//
			// if ("GEN_INSTALL1".equals(name)) {
			// String msg =
			// String.format(StringResource.get("MSG_GENERIC_ADDON_DESC1"),
			// oldSignedPrivateMozillaExt,
			// ffAMOURL);
			// MessageBox.show(parent, StringResource.get("MSG_ADDON_TITLE"),
			// msg, MessageBox.OK_OPTION,
			// MessageBox.OK);
			// }
			// if ("GEN_INSTALL2".equals(name)) {
			// String msg =
			// String.format(StringResource.get("MSG_GENERIC_ADDON_DESC2"),
			// chromeWebStoreURL,
			// directCRXURL);
			// MessageBox.show(parent, StringResource.get("MSG_ADDON_TITLE"),
			// msg, MessageBox.OK_OPTION,
			// MessageBox.OK);
			// }
			if (setPage(name)) {
				level = 1;
				SwingUtilities.invokeLater(new Runnable() {
					public void run() {
						jsp.getVerticalScrollBar().setValue(0);
					}
				});
			}
		}
	}

	private void loadSchedulerSettings(int selectedQ) {
		queueModel.clear();
		for (DownloadQueue q : QueueManager.getInstance().getQueueList()) {
			queueModel.addElement(q);
		}
		qList.setSelectedIndex(selectedQ);
		qList.ensureIndexIsVisible(selectedQ);
	}

	private void loadOverviewSettings() {
		Config config = Config.getInstance();
		chkPrgWnd.setSelected(config.showDownloadWindow());
		chkEndWnd.setSelected(config.showDownloadCompleteWindow());
		chkOverwriteExisting.setSelected(config.getDuplicateAction() == XDMConstants.DUP_ACT_OVERWRITE);
		Logger.log("Max download: " + config.getMaxDownloads());
		cmbMax.setSelectedItem(config.getMaxDownloads() > 0 ? config.getMaxDownloads() + "" : "N/A");
		// cmbDupAction.setSelectedIndex(config.getDuplicateAction());
		txtTempFolder.setText(config.getTemporaryFolder());
		cmbCategory.setSelectedIndex(0);
		txtDefFolder.setText(config.isForceSingleFolder() ? config.getDownloadFolder() : config.getCategoryOther());
		chkNoTransparency.setSelected(config.isNoTransparency());
		chkVidBrowserOnly.setSelected(config.isShowVideoListOnlyInBrowser());
		chkForceFolder.setSelected(config.isForceSingleFolder());
		cmbCategory.setEnabled(!config.isForceSingleFolder());
	}

	private void loadMonitoringSettings() {
		Config config = Config.getInstance();
		txtFileTyp.setText(XDMUtils.appendArray2Str(config.getFileExts()));
		txtVidType.setText(XDMUtils.appendArray2Str(config.getVidExts()));
		txtBlockedHosts.setText(XDMUtils.appendArray2Str(config.getBlockedHosts()));
		chkVidPan.setSelected(config.isShowVideoNotification());
		chkMonitorClipboard.setSelected(config.isMonitorClipboard());
		int index = -1;
		int sz = config.getMinVidSize();
		for (int i = 0; i < sizeArr.length; i++) {
			if (sz == sizeArr[i]) {
				index = i;
			}
		}
		if (index < 0) {
			index = 2;
		}

		cmbMinVidSize.setSelectedIndex(index);
		chkDwnAuto.setSelected(config.isDownloadAutoStart());
		chkGetTs.setSelected(config.isFetchTs());
	}

	private void loadNetworkSettings() {
		Config config = Config.getInstance();
		cmbSeg.setSelectedItem(config.getMaxSegments() + "");
		cmbTimeout.setSelectedItem(config.getNetworkTimeout() > 1 ? config.getNetworkTimeout() + "" : "N/A");
		String val = "Default";
		int ival = config.getTcpWindowSize();
		if (ival > 0) {
			val = ival + "";
		}
		cmbTcp.setSelectedItem(val);
		// txtSpeedLimit.setText(config.getSpeedLimit() < 1 ? "N/A" :
		// config.getSpeedLimit() + "");
		int proxyMode = config.getProxyMode();
		if (proxyMode == 0) {
			chkUsePac.setSelected(false);
			chkUseProxy.setSelected(false);
			chkUseSocks.setSelected(false);
		} else if (proxyMode == 1) {
			chkUsePac.setSelected(true);
		} else if (proxyMode == 2) {
			chkUseProxy.setSelected(true);
		} else if (proxyMode == 3) {
			chkUseSocks.setSelected(true);
		}

		txtPACUrl.setText(config.getProxyPac());
		if (config.getProxyHost() == null || config.getProxyHost().length() < 1) {
			txtProxyHostnPort.setText("");
		} else {
			txtProxyHostnPort
					.setText(config.getProxyHost() + (config.getProxyPort() > 0 ? ":" + config.getProxyPort() : ""));
		}

		if (config.getSocksHost() == null || config.getSocksHost().length() < 1) {
			txtSocksHostnPort.setText("");
		} else {
			txtSocksHostnPort
					.setText(config.getSocksHost() + (config.getSocksPort() > 0 ? ":" + config.getSocksPort() : ""));
		}

		if (config.getProxyUser() == null || config.getProxyUser().length() < 1) {
			txtProxyUser.setText("");
			txtProxyPass.setText("");
		} else {
			txtProxyUser.setText(config.getProxyUser());
			if (config.getProxyPass() == null || config.getProxyPass().length() < 1) {
				txtProxyPass.setText("");
			} else {
				txtProxyPass.setText(config.getProxyPass());
			}
		}
	}

	private void loadPasswordSettings() {
		passListModel.clear();
		txtCredHostName.setText("");
		txtUserName.setText("");
		txtPassword.setText("");
		Set<Entry<String, PasswordAuthentication>> credentials = CredentialManager.getInstance().getCredentials();
		Iterator<Entry<String, PasswordAuthentication>> it = credentials.iterator();
		while (it.hasNext()) {
			Entry<String, PasswordAuthentication> ent = it.next();
			PasswordItem item = new PasswordItem();
			item.host = ent.getKey();
			item.user = ent.getValue().getUserName();
			item.password = new String(ent.getValue().getPassword());
			passListModel.addElement(item);
		}
	}

	private void savePasswordSettings() {
		String host = txtCredHostName.getText();
		String user = txtUserName.getText();
		String password = txtPassword.getText();

		Logger.log(host + " " + user);

		if (StringUtils.isNullOrEmptyOrBlank(host) || StringUtils.isNullOrEmptyOrBlank(user)) {
			return;
		}
		CredentialManager mgr = CredentialManager.getInstance();
		mgr.addCredentialForHost(host, user, password, true);
		mgr.save();
		loadPasswordSettings();
	}

	private boolean setPage(String name) {
		boolean pageFound = true;
		if ("PG_SETTINGS".equals(name)) {
			if (overviewPanel == null) {
				overviewPanel = createOverviewPanel();
			}
			jsp.setViewportView(overviewPanel);
			titleLbl.setText(StringResource.get("TITLE_SETTINGS"));
			loadOverviewSettings();
			currentPage = overviewPanel;
		} else if ("BTN_MONITORING".equals(name)) {
			if (browserIntPanel == null) {
				browserIntPanel = createBrowserIntPanel();
			}
			jsp.setViewportView(browserIntPanel);
			titleLbl.setText(StringResource.get("SETTINGS_MONITORING"));
			loadMonitoringSettings();
			currentPage = browserIntPanel;
		} else if ("BTN_NETWORK".equals(name)) {
			if (networkPanel == null) {
				networkPanel = createNetworkPanel();
			}
			jsp.setViewportView(networkPanel);
			titleLbl.setText(StringResource.get("DESC_NET_TITLE"));
			loadNetworkSettings();
			currentPage = networkPanel;
		} else if ("PASS_MAN".equals(name)) {
			if (passwordPanel == null) {
				passwordPanel = createPasswordPanel();
			}
			jsp.setViewportView(passwordPanel);
			titleLbl.setText(StringResource.get("DESC_PASS_TITLE"));
			loadPasswordSettings();
			currentPage = passwordPanel;
		} else if ("Q_MAN".equals(name)) {
			if (queuePanel == null) {
				queuePanel = createSchedulerPanel();
			}
			loadSchedulerSettings(0);
			jsp.setViewportView(queuePanel);
			titleLbl.setText(StringResource.get("DESC_Q_TITLE"));
			currentPage = queuePanel;
		} else if ("ADV_MAN".equals(name)) {
			if (advPanel == null) {
				advPanel = createAdvPanel();
			}
			jsp.setViewportView(advPanel);
			titleLbl.setText(StringResource.get("DESC_ADV_TITLE"));
			loadAdvSettings();
			currentPage = advPanel;
		} else {
			pageFound = false;
		}
		return pageFound;
	}

	private JTextArea createTextArea(String name, Font font) {
		JTextArea textArea = new JTextArea();
		textArea.setOpaque(false);
		textArea.setWrapStyleWord(true);
		textArea.setLineWrap(true);
		textArea.setEditable(false);
		textArea.setForeground(Color.WHITE);
		textArea.setText(StringResource.get(name));
		textArea.setFont(font);
		return textArea;
	}

	private JTextArea createTextArea(String name) {
		return createTextArea(name, FontResource.getNormalFont());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private JPanel createBrowserIntPanel() {
		JPanel p = new JPanel();
		p.setLayout(null);
		p.setOpaque(false);

		int y = getScaledInt(30);
		int h = getScaledInt(70);

		JTextArea txt1 = createTextArea("DESC_MONITORING_1");
		txt1.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(txt1);
		y += h;
		y += getScaledInt(30);

		h = getScaledInt(25);
		JLabel lblFirefox = new JLabel(StringResource.get("DESC_MOZILLA_FIREFOX"));
		lblFirefox.setFont(FontResource.getBigFont());
		lblFirefox.setBounds(getScaledInt(15), y, getScaledInt(135), h);
		p.add(lblFirefox);

		JLabel lblChrome = new JLabel(StringResource.get("DESC_GOOGLE_CHROME"));
		lblChrome.setFont(FontResource.getBigFont());
		lblChrome.setBounds(getScaledInt(180), y, getScaledInt(135), h);
		p.add(lblChrome);

		y += h;

		JButton btnFF = createButton2("DESC_INSTALL");
		btnFF.setName("FF_INSTALL");
		btnFF.setBounds(getScaledInt(15), y, getScaledInt(140), h);
		p.add(btnFF);
		JButton btnCr = createButton2("DESC_INSTALL");
		btnCr.setName("CR_INSTALL");
		btnCr.setBounds(getScaledInt(180), y, getScaledInt(140), h);
		p.add(btnCr);
		y += h;

		y += getScaledInt(15);
		h = getScaledInt(25);

		JLabel lblOpera = new JLabel(StringResource.get("DESC_OPERA"));
		lblOpera.setFont(FontResource.getBigFont());
		lblOpera.setBounds(getScaledInt(15), y, getScaledInt(135), h);
		p.add(lblOpera);

		JLabel lblCm = new JLabel(StringResource.get("DESC_CHROMIUM"));
		lblCm.setFont(FontResource.getBigFont());
		lblCm.setBounds(getScaledInt(180), y, getScaledInt(135), h);
		p.add(lblCm);

		y += h;

		JButton btnOp = createButton2("DESC_INSTALL");
		btnOp.setName("OP_INSTALL");
		btnOp.setBounds(getScaledInt(15), y, getScaledInt(140), h);
		p.add(btnOp);
		JButton btnCm = createButton2("DESC_INSTALL");
		btnCm.setName("CM_INSTALL");
		btnCm.setBounds(getScaledInt(180), y, getScaledInt(140), h);
		p.add(btnCm);
		y += h;

		y += getScaledInt(15);
		h = getScaledInt(25);

		JLabel lblEdge = new JLabel(StringResource.get("DESC_VIVALDI"));
		lblEdge.setFont(FontResource.getBigFont());
		lblEdge.setName("VL_INSTALL");
		lblEdge.setBounds(getScaledInt(15), y, getScaledInt(135), h);
		p.add(lblEdge);

		JLabel lblEdge2 = new JLabel(StringResource.get("DESC_MS_EDGE"));
		lblEdge2.setFont(FontResource.getBigFont());
		lblEdge2.setBounds(getScaledInt(180), y, getScaledInt(135), h);
		p.add(lblEdge2);

		y += h;
		JButton btnEdge = createButton2("DESC_INSTALL");
		btnEdge.setName("VL_INSTALL");
		// btnEdge.addActionListener(this);
		btnEdge.setBounds(getScaledInt(15), y, getScaledInt(140), h);
		p.add(btnEdge);

		JButton btnEdge2 = createButton2("DESC_INSTRUCTION");
		btnEdge2.setName("EDGE_INSTALL");
		// btnEdge2.addActionListener(this);
		btnEdge2.setBounds(getScaledInt(180), y, getScaledInt(140), h);
		p.add(btnEdge2);
		y += h;
		y += getScaledInt(40);

		h = getScaledInt(70);
		JTextArea txt2 = createTextArea("DESC_OTHER_BROWSERS");
		txt2.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(txt2);
		y += h;

		h = getScaledInt(30);
		JLabel labelMoz = new JLabel(StringResource.get("DESC_MOZ"));
		labelMoz.setCursor(new Cursor(Cursor.HAND_CURSOR));
		labelMoz.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				showMsgFF();
			}
		});
		Font font = FontResource.getNormalFont();
		Map attributes = font.getAttributes();
		attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
		labelMoz.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		labelMoz.setFont(font.deriveFont(attributes));
		labelMoz.setForeground(Color.WHITE);
		p.add(labelMoz);
		y += h;

		h = getScaledInt(30);
		JLabel labelCr = new JLabel(StringResource.get("DESC_CHROME"));
		labelCr.setCursor(new Cursor(Cursor.HAND_CURSOR));
		labelCr.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				showMsgChrome();
			}
		});
		labelCr.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		labelCr.setFont(font.deriveFont(attributes));
		labelCr.setForeground(Color.WHITE);
		p.add(labelCr);
		y += h;
		y += getScaledInt(40);

		h = getScaledInt(40);
		JTextArea txt3 = createTextArea("DESC_FILETYPES");
		txt3.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		p.add(txt3);
		y += h;

		h = getScaledInt(70);
		txtFileTyp = new JTextArea();
		txtFileTyp.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		txtFileTyp.setOpaque(false);
		txtFileTyp.setWrapStyleWord(true);
		txtFileTyp.setLineWrap(true);
		txtFileTyp.setForeground(Color.WHITE);
		txtFileTyp.setFont(FontResource.getNormalFont());
		txtFileTyp.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		p.add(txtFileTyp);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(25);
		JButton btnDef1 = createButton2("DESC_DEF");
		btnDef1.setName("BTN_DEF_FILE_EXT");
		btnDef1.setBounds(getScaledInt(310) - getScaledInt(125), y, getScaledInt(140), h);
		p.add(btnDef1);
		y += h;

		y += getScaledInt(40);

		h = getScaledInt(30);
		chkVidPan = createCheckBox("OPT_VID_PANE");
		chkVidPan.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(chkVidPan);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(40);
		JTextArea txt4 = createTextArea("DESC_VIDEOTYPES");
		txt4.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		p.add(txt4);
		y += h;

		h = getScaledInt(70);
		txtVidType = new JTextArea();
		txtVidType.setOpaque(false);
		txtVidType.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		txtVidType.setWrapStyleWord(true);
		txtVidType.setLineWrap(true);
		txtVidType.setForeground(Color.WHITE);
		txtVidType.setFont(FontResource.getNormalFont());
		txtVidType.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		p.add(txtVidType);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(25);
		JButton btnDef2 = createButton2("DESC_DEF");
		btnDef2.setName("BTN_DEF_VID_EXT");
		btnDef2.setBounds(getScaledInt(310) - getScaledInt(125), y, getScaledInt(140), h);
		p.add(btnDef2);
		y += h;

		y += getScaledInt(30);

		JLabel lblMinVidSize = new JLabel(StringResource.get("LBL_MIN_VIDEO_SIZE"));
		lblMinVidSize.setForeground(Color.WHITE);
		lblMinVidSize.setFont(FontResource.getNormalFont());
		lblMinVidSize.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lblMinVidSize);
		h += getScaledInt(30);

		h = getScaledInt(25);
		cmbMinVidSize = new JComboBox<String>(new String[] { "N/A", "512 KB", "1 MB", "5 MB", "10 MB" });
		cmbMinVidSize.setBackground(ColorResource.getDarkerBgColor());
		cmbMinVidSize.setBounds(getScaledInt(250), y, getScaledInt(75), h);
		cmbMinVidSize.setRenderer(new SimpleListRenderer());
		p.add(cmbMinVidSize);
		y += h;
		y += getScaledInt(10);

		y += getScaledInt(20);

		h = getScaledInt(40);
		JTextArea txt6 = createTextArea("DESC_SITEEXCEPTIONS");
		txt6.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(txt6);
		y += h;

		h = getScaledInt(70);
		txtBlockedHosts = new JTextArea();
		txtBlockedHosts.setOpaque(false);
		txtBlockedHosts.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		txtBlockedHosts.setWrapStyleWord(true);
		txtBlockedHosts.setLineWrap(true);
		txtBlockedHosts.setForeground(Color.WHITE);
		txtBlockedHosts.setFont(FontResource.getNormalFont());
		txtBlockedHosts.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		p.add(txtBlockedHosts);
		y += h;

		y += getScaledInt(20);

		h = getScaledInt(30);
		chkMonitorClipboard = createCheckBox("MENU_CLIP_ADD");
		chkMonitorClipboard.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkMonitorClipboard);
		y += h;

		y += getScaledInt(10);

		h = getScaledInt(30);
		chkDwnAuto = createCheckBox("LBL_START_AUTO");
		chkDwnAuto.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkDwnAuto);
		y += h;

		y += getScaledInt(10);

		h = getScaledInt(30);
		chkGetTs = createCheckBox("LBL_GET_TIMESTAMP");
		chkGetTs.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkGetTs);
		y += h;

		y += getScaledInt(50);

		p.setPreferredSize(new Dimension(getScaledInt(350), y));

		return p;
	}

	private JPanel createNetworkPanel() {
		JPanel p = new JPanel();
		p.setLayout(null);
		p.setOpaque(false);

		int y = getScaledInt(20);
		int h = getScaledInt(50);

		h = getScaledInt(30);
		JLabel lbl1 = new JLabel(StringResource.get("DESC_NET"));
		lbl1.setForeground(Color.WHITE);
		lbl1.setFont(FontResource.getItemFont());
		lbl1.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl1);
		y += h;

		y += getScaledInt(20);
		h = getScaledInt(25);
		JLabel lbl2 = new JLabel(StringResource.get("DESC_NET1"));
		lbl2.setForeground(Color.WHITE);
		lbl2.setFont(FontResource.getNormalFont());
		lbl2.setBounds(getScaledInt(15), y, getScaledInt(200), h);
		p.add(lbl2);
		y += getScaledInt(5);

		h = getScaledInt(20);
		cmbTimeout = new JComboBox<String>(new String[] { "10", "30", "60", "120", "180", "360", "N/A" });
		cmbTimeout.setBackground(ColorResource.getDarkerBgColor());
		cmbTimeout.setBounds(getScaledInt(250), y, getScaledInt(75), h);
		cmbTimeout.setRenderer(new SimpleListRenderer());
		p.add(cmbTimeout);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(25);
		JLabel lbl3 = new JLabel(StringResource.get("DESC_NET2"));
		lbl3.setForeground(Color.WHITE);
		lbl3.setFont(FontResource.getNormalFont());
		lbl3.setBounds(getScaledInt(15), y, getScaledInt(200), h);
		p.add(lbl3);
		y += getScaledInt(5);

		h = getScaledInt(20);
		cmbSeg = new JComboBox<String>(new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
				"13", "14", "15", "16", "20", "25", "30", "32" });

		cmbSeg.setBackground(ColorResource.getDarkerBgColor());
		cmbSeg.setBounds(getScaledInt(250), y, getScaledInt(75), h);
		cmbSeg.setRenderer(new SimpleListRenderer());
		p.add(cmbSeg);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(25);
		JLabel lbl4 = new JLabel(StringResource.get("DESC_NET3"));
		lbl4.setForeground(Color.WHITE);
		lbl4.setFont(FontResource.getNormalFont());
		lbl4.setBounds(getScaledInt(15), y, getScaledInt(200), h);
		p.add(lbl4);
		y += getScaledInt(5);

		h = getScaledInt(20);
		cmbTcp = new JComboBox<String>(
				new String[] { "Default", "8", "16", "32", "64", "128", "256", "512", "1024", "2048", "4096", "8192" });

		cmbTcp.setBackground(ColorResource.getDarkerBgColor());
		cmbTcp.setBounds(getScaledInt(250), y, getScaledInt(75), h);
		cmbTcp.setRenderer(new SimpleListRenderer());
		p.add(cmbTcp);
		y += h;

		y += getScaledInt(50);

		// h = getScaledInt(30);
		// JLabel lbl51 = new JLabel(StringResource.get("SPEED_LIMIT_TITLE"));
		// lbl51.setForeground(Color.WHITE);
		// lbl51.setFont(FontResource.getItemFont());
		// lbl51.setBounds(getScaledInt(15), y, getScaledInt(350) -
		// getScaledInt(30),
		// h);
		// p.add(lbl51);
		// y += h;
		//
		// h = getScaledInt(30);
		// JLabel lbl71 = new JLabel(StringResource.get("MSG_SPEED_LIMIT"));
		// lbl71.setForeground(Color.WHITE);
		// lbl71.setFont(FontResource.getNormalFont());
		// lbl71.setBounds(getScaledInt(15), y, getScaledInt(350) -
		// getScaledInt(30),
		// h);
		// p.add(lbl71);
		// y += h;
		//
		// h = getScaledInt(25);
		// txtSpeedLimit = new JTextField();
		// txtSpeedLimit.setBounds(getScaledInt(15), y, getScaledInt(350) -
		// getScaledInt(250), h);
		// txtSpeedLimit.setBorder(new
		// LineBorder(ColorResource.getDarkBtnColor()));
		// txtSpeedLimit.setEditable(true);
		// txtSpeedLimit.setCaretColor(ColorResource.getActiveTabColor());
		// txtSpeedLimit.setForeground(Color.WHITE);
		// txtSpeedLimit.setOpaque(false);
		// p.add(txtSpeedLimit);
		// y += h;
		//
		// y += getScaledInt(50);

		h = getScaledInt(30);
		JLabel lbl5 = new JLabel(StringResource.get("DESC_NET4"));
		lbl5.setForeground(Color.WHITE);
		lbl5.setFont(FontResource.getItemFont());
		lbl5.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl5);
		y += h;

		// y += getScaledInt(10);
		h = getScaledInt(30);
		chkUsePac = createCheckBox("DESC_NET5");
		chkUsePac.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(chkUsePac);
		chkUsePac.setVisible(false);
		// y += h;

		h = getScaledInt(25);
		txtPACUrl = new JTextField();
		txtPACUrl.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtPACUrl.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtPACUrl.setEditable(true);
		txtPACUrl.setForeground(Color.WHITE);
		txtPACUrl.setCaretColor(ColorResource.getActiveTabColor());
		txtPACUrl.setOpaque(false);
		p.add(txtPACUrl);
		// y += h;
		txtPACUrl.setVisible(false);

		// y += getScaledInt(10);
		h = getScaledInt(30);
		chkUseProxy = createCheckBox("DESC_NET6");
		chkUseProxy.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(chkUseProxy);
		y += h;

		h = getScaledInt(25);
		txtProxyHostnPort = new JTextField();
		txtProxyHostnPort.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtProxyHostnPort.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtProxyHostnPort.setEditable(true);
		txtProxyHostnPort.setCaretColor(ColorResource.getActiveTabColor());
		txtProxyHostnPort.setForeground(Color.WHITE);
		txtProxyHostnPort.setOpaque(false);
		p.add(txtProxyHostnPort);
		y += h;

		y += getScaledInt(10);
		h = getScaledInt(30);
		chkUseSocks = createCheckBox("DESC_NET9");
		chkUseSocks.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(chkUseSocks);
		y += h;

		h = getScaledInt(25);
		txtSocksHostnPort = new JTextField();
		txtSocksHostnPort.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtSocksHostnPort.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtSocksHostnPort.setEditable(true);
		txtSocksHostnPort.setCaretColor(ColorResource.getActiveTabColor());
		txtSocksHostnPort.setForeground(Color.WHITE);
		txtSocksHostnPort.setOpaque(false);
		p.add(txtSocksHostnPort);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(30);
		JLabel lbl6 = new JLabel(StringResource.get("DESC_NET7"));
		lbl6.setForeground(Color.WHITE);
		lbl6.setFont(FontResource.getNormalFont());
		lbl6.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl6);
		y += h;

		h = getScaledInt(25);
		txtProxyUser = new JTextField();
		txtProxyUser.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtProxyUser.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtProxyUser.setCaretColor(ColorResource.getActiveTabColor());
		txtProxyUser.setEditable(true);
		txtProxyUser.setForeground(Color.WHITE);
		txtProxyUser.setOpaque(false);
		p.add(txtProxyUser);
		y += h;
		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lbl7 = new JLabel(StringResource.get("DESC_NET8"));
		lbl7.setForeground(Color.WHITE);
		lbl7.setFont(FontResource.getNormalFont());
		lbl7.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl7);
		y += h;

		h = getScaledInt(25);
		txtProxyPass = new JPasswordField();
		txtProxyPass.setBounds(getScaledInt(15), y, getScaledInt(320) - getScaledInt(10), h);
		txtProxyPass.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtProxyPass.setCaretColor(ColorResource.getActiveTabColor());
		txtProxyPass.setEditable(true);
		txtProxyPass.setForeground(Color.WHITE);
		txtProxyPass.setOpaque(false);
		p.add(txtProxyPass);
		y += h;

		y += getScaledInt(50);

		p.setPreferredSize(new Dimension(getScaledInt(350), y));

		return p;
	}

	private JPanel createPasswordPanel() {
		JPanel p = new JPanel();
		p.setLayout(null);
		p.setOpaque(false);

		int y = getScaledInt(20);
		int h = getScaledInt(100);

		passListModel = new DefaultListModel<>();

		passList = new JList<>(passListModel);
		passList.addListSelectionListener(new ListSelectionListener() {

			@Override
			public void valueChanged(ListSelectionEvent e) {

				int index = passList.getSelectedIndex();
				if (index > -1 && index < passListModel.size()) {
					PasswordItem item = passListModel.get(index);
					txtCredHostName.setText(item.host);
					txtUserName.setText(item.user);
					txtPassword.setText(item.password);
				}
			}
		});
		passList.setCellRenderer(new SimpleListRenderer());
		passList.setBorder(null);
		passList.setOpaque(false);

		JScrollPane jsp2 = new JScrollPane();
		jsp2.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		jsp2.getViewport().setOpaque(false);
		jsp2.setViewportView(passList);
		jsp2.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		jsp2.setOpaque(false);
		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp2.setVerticalScrollBar(scrollBar);
		jsp2.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		p.add(jsp2);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(25);
		JButton btnDel = createButton2("DESC_PASS_DEL");
		btnDel.setBounds(getScaledInt(350) - getScaledInt(10) - getScaledInt(80) - getScaledInt(15), y,
				getScaledInt(80), h);
		btnDel.setName("DEL_CRED");
		p.add(btnDel);
		JButton btnNew = createButton2("DESC_PASS_NEW");
		btnNew.setName("NEW_CRED");
		btnNew.setBounds(getScaledInt(350) - getScaledInt(10) - getScaledInt(80) - getScaledInt(90) - getScaledInt(15),
				y, getScaledInt(80), h);
		p.add(btnNew);
		y += h;

		h = getScaledInt(30);
		JLabel lbl64 = new JLabel(StringResource.get("DESC_HOST"));
		lbl64.setForeground(Color.WHITE);
		lbl64.setFont(FontResource.getNormalFont());
		lbl64.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl64);
		y += h;

		h = getScaledInt(25);
		txtCredHostName = new JTextField();
		txtCredHostName.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtCredHostName.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtCredHostName.setForeground(Color.WHITE);
		txtCredHostName.setOpaque(false);
		p.add(txtCredHostName);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(30);
		JLabel lbl6 = new JLabel(StringResource.get("DESC_USER"));
		lbl6.setForeground(Color.WHITE);
		lbl6.setFont(FontResource.getNormalFont());
		lbl6.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl6);
		y += h;

		h = getScaledInt(25);
		txtUserName = new JTextField();
		txtUserName.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtUserName.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtUserName.setForeground(Color.WHITE);
		txtUserName.setOpaque(false);
		p.add(txtUserName);
		y += h;
		y += getScaledInt(10);
		h = getScaledInt(30);
		JLabel lbl7 = new JLabel(StringResource.get("DESC_PASS"));
		lbl7.setForeground(Color.WHITE);
		lbl7.setFont(FontResource.getNormalFont());
		lbl7.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl7);
		y += h;

		h = getScaledInt(25);
		txtPassword = new JPasswordField();
		txtPassword.setBounds(getScaledInt(15), y, getScaledInt(320) - getScaledInt(10), h);
		txtPassword.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtPassword.setForeground(Color.WHITE);
		txtPassword.setOpaque(false);
		p.add(txtPassword);
		y += h;
		y += getScaledInt(10);

		JButton btnSave = createButton2("DESC_PASS_SAVE");
		btnSave.setName("SAVE_CRED");
		btnSave.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		p.add(btnSave);
		y += h;

		y += getScaledInt(50);

		p.setPreferredSize(new Dimension(getScaledInt(350), y));
		return p;
	}

	private JPanel createSchedulerPanel() {
		JPanel p = new JPanel();
		p.setLayout(null);
		p.setOpaque(false);

		int y = getScaledInt(5);

		h = getScaledInt(30);
		JLabel lbl67 = new JLabel(StringResource.get("Q_LIST_DESC"));
		lbl67.setForeground(Color.WHITE);
		lbl67.setFont(FontResource.getNormalFont());
		lbl67.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl67);
		y += h;

		int h = getScaledInt(100);

		queueModel = new DefaultListModel<DownloadQueue>();
		qList = new JList<DownloadQueue>(queueModel);
		qList.setCellRenderer(new QueueListRenderer());
		qList.setBorder(null);
		qList.setOpaque(false);
		qList.setSelectedIndex(0);
		qList.addListSelectionListener(this);

		JScrollPane jsp2 = new JScrollPane();
		jsp2.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		jsp2.getViewport().setOpaque(false);
		jsp2.setViewportView(qList);
		jsp2.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		jsp2.setOpaque(false);
		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp2.setVerticalScrollBar(scrollBar);
		jsp2.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		p.add(jsp2);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(25);
		JButton btnSave = createButton2("DESC_PASS_NEW");
		btnSave.setName("BTN_Q_NEW");
		btnSave.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		p.add(btnSave);
		JButton btnDel = createButton2("DESC_PASS_DEL");
		btnDel.setName("BTN_Q_DEL");
		btnDel.setBounds(getScaledInt(15) + getScaledInt(80) + getScaledInt(10), y, getScaledInt(80), h);
		p.add(btnDel);
		JButton btnNew = createButton2("DESC_SAVE_Q");
		btnNew.setName("BTN_Q_SAVE");
		btnNew.setBounds(getScaledInt(350) - getScaledInt(25) - getScaledInt(80), y, getScaledInt(80), h);
		p.add(btnNew);
		y += h;
		y += getScaledInt(20);

		h = getScaledInt(30);
		JLabel lbl6 = new JLabel(StringResource.get("MSG_QNAME"));
		lbl6.setForeground(Color.WHITE);
		lbl6.setFont(FontResource.getNormalFont());
		lbl6.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl6);
		y += h;

		h = getScaledInt(25);
		txtQueueName = new JTextField();
		txtQueueName.setCaretColor(Color.WHITE);
		txtQueueName.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtQueueName.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtQueueName.setEditable(true);
		txtQueueName.setForeground(Color.WHITE);
		txtQueueName.setOpaque(false);
		p.add(txtQueueName);
		y += h;
		y += getScaledInt(20);

		h = getScaledInt(30);
		JLabel lbl69 = new JLabel(StringResource.get("Q_LIST_FILES"));
		lbl69.setForeground(Color.WHITE);
		lbl69.setFont(FontResource.getNormalFont());
		lbl69.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl69);
		y += h;

		h = getScaledInt(100);

		queuedItemsModel = new DefaultListModel<String>();
		qItemsList = new JList<String>(queuedItemsModel);
		qItemsList.setCellRenderer(new QueuedItemsRenderer());
		qItemsList.setBorder(null);
		qItemsList.setOpaque(false);
		qItemsList.addListSelectionListener(this);

		JScrollPane jsp3 = new JScrollPane();
		jsp3.setBorder(new LineBorder(ColorResource.getDarkBgColor()));
		jsp3.getViewport().setOpaque(false);
		jsp3.setViewportView(qItemsList);
		jsp3.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(40), h);
		jsp3.setOpaque(false);
		DarkScrollBar scrollBar2 = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp3.setVerticalScrollBar(scrollBar2);
		jsp3.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		jsp3.setAutoscrolls(true);
		p.add(jsp3);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(25);
		JButton btnQMoveUp = createButton2("Q_MOVE_UP");
		btnQMoveUp.setName("Q_MOVE_UP");
		btnQMoveUp.setBounds(getScaledInt(15), y, getScaledInt(100), h);
		p.add(btnQMoveUp);
		JButton btnQMoveDown = createButton2("Q_MOVE_DN");
		btnQMoveDown.setName("Q_MOVE_DN");
		btnQMoveDown.setBounds(getScaledInt(15) + getScaledInt(100) + getScaledInt(5), y, getScaledInt(100), h);
		p.add(btnQMoveDown);
		btnQMoveTo = createButton2("Q_MOVE_TO");
		btnQMoveTo.setName("Q_MOVE_TO");
		btnQMoveTo.setBounds(getScaledInt(350) - getScaledInt(25) - getScaledInt(100), y, getScaledInt(100), h);
		p.add(btnQMoveTo);
		y += h;
		y += getScaledInt(20);

		h = getScaledInt(30);
		JLabel lbl68 = new JLabel(StringResource.get("Q_SCHEDULE_TXT"));
		lbl68.setForeground(Color.WHITE);
		lbl68.setFont(FontResource.getNormalFont());
		lbl68.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl68);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(20);
		chkQStart = createCheckBox("MSG_Q_START");
		chkQStart.setBounds(getScaledInt(15), y, getScaledInt(150), h);
		chkQStart.addActionListener(this);
		p.add(chkQStart);

		spinnerDateModel1 = new SpinnerDateModel(new Date(), null, null, Calendar.HOUR_OF_DAY);
		spStartTime = new JSpinner(spinnerDateModel1);
		spStartTime.setForeground(Color.WHITE);
		spStartTime.setBackground(ColorResource.getDarkBgColor());
		spStartTime.setBorder(null);
		JSpinner.DateEditor ed1 = new JSpinner.DateEditor(spStartTime, "hh:mm a");
		int n = ed1.getComponentCount();
		for (int i = 0; i < n; i++) {
			Component c = ed1.getComponent(i);
			if (c instanceof JTextField) {
				c.setForeground(Color.WHITE);
				c.setBackground(ColorResource.getDarkBtnColor());
			}
		}

		spStartTime.setEditor(ed1);
		spStartTime.setBounds(getScaledInt(210), y, getScaledInt(115), h);
		p.add(spStartTime);
		y += h;
		y += getScaledInt(5);

		h = getScaledInt(20);
		chkQStop = createCheckBox("MSG_Q_STOP");
		chkQStop.setBounds(getScaledInt(15), y, getScaledInt(150), h);
		p.add(chkQStop);
		spinnerDateModel2 = new SpinnerDateModel(new Date(), null, null, Calendar.HOUR_OF_DAY);
		spEndTime = new JSpinner(spinnerDateModel2);
		spEndTime.setBorder(null);
		JSpinner.DateEditor ed2 = new JSpinner.DateEditor(spEndTime, "hh:mm a");
		n = ed2.getComponentCount();
		for (int i = 0; i < n; i++) {
			Component c = ed2.getComponent(i);
			if (c instanceof JTextField) {
				c.setForeground(Color.WHITE);
				c.setBackground(ColorResource.getDarkBtnColor());
			}
		}
		spEndTime.setEditor(ed2);
		spEndTime.setBounds(getScaledInt(210), y, getScaledInt(115), h);
		p.add(spEndTime);

		y += h;

		y += getScaledInt(20);

		ButtonGroup radioGroup = new ButtonGroup();

		h = getScaledInt(30);
		radOnetime = createRadioButton("MSQ_Q_ONETIME", FontResource.getNormalFont());
		radOnetime.setName("Q_ONCE");
		radOnetime.addActionListener(this);
		radOnetime.setBounds(getScaledInt(15), y, getScaledInt(120), h);
		p.add(radOnetime);
		radOnetime.setEnabled(false);
		radioGroup.add(radOnetime);
		y += getScaledInt(5);

		h = getScaledInt(20);
		spinnerDateModel3 = new SpinnerDateModel(new Date(), DateTimeUtils.getBeginDate(), DateTimeUtils.getEndDate(),
				Calendar.DAY_OF_MONTH);
		spExecDate = new JSpinner(spinnerDateModel3);
		spExecDate.setBorder(null);
		JSpinner.DateEditor ed3 = new JSpinner.DateEditor(spExecDate, "dd-MMM-yy");
		n = ed3.getComponentCount();
		for (int i = 0; i < n; i++) {
			Component c = ed3.getComponent(i);
			if (c instanceof JTextField) {
				c.setForeground(Color.WHITE);
				c.setBackground(ColorResource.getDarkBtnColor());
			}
		}
		spExecDate.setEditor(ed3);
		spExecDate.setBounds(getScaledInt(120), y, getScaledInt(205), h);
		p.add(spExecDate);
		y += h;
		y += getScaledInt(15);

		radPeriodic = createRadioButton("MSG_Q_DAILY", FontResource.getNormalFont());
		radPeriodic.setName("Q_DAILY");
		radPeriodic.addActionListener(this);
		radPeriodic.setBounds(getScaledInt(15), y, getScaledInt(100), h);
		p.add(radPeriodic);
		radioGroup.add(radPeriodic);

		h = getScaledInt(20);
		int x = getScaledInt(120);
		chkDays = new JCheckBox[7];
		for (int i = 1; i < 8; i++) {
			JCheckBox chkDay = createCheckBox("MSG_Q_D" + i, FontResource.getNormalFont());
			chkDay.setBounds(x, y, getScaledInt(100), h);
			p.add(chkDay);
			chkDays[i - 1] = chkDay;
			x += getScaledInt(100);
			if (i % 2 == 0) {
				x = getScaledInt(120);
				y += h;
			}
		}

		y += h;
		y += getScaledInt(20);

		y += getScaledInt(50);

		p.setPreferredSize(new Dimension(getScaledInt(350), y));
		return p;
	}

	private JPanel createAdvPanel() {
		JPanel p = new JPanel();
		p.setLayout(null);
		p.setOpaque(false);

		int y = getScaledInt(20);
		int h = 0;
		y += h;

		h = getScaledInt(30);
		chkHaltAfterFinish = createCheckBox("MSG_HALT");
		chkHaltAfterFinish.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkHaltAfterFinish);
		y += h;

		h = getScaledInt(30);
		chkKeepAwake = createCheckBox("MSG_AWAKE");
		chkKeepAwake.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkKeepAwake);
		y += h;

		h = getScaledInt(30);
		chkExecCmd = createCheckBox("EXEC_CMD");
		chkExecCmd.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkExecCmd);
		y += h;

		h = getScaledInt(25);
		txtCustomCmd = new JTextField();
		txtCustomCmd.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtCustomCmd.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtCustomCmd.setForeground(Color.WHITE);
		txtCustomCmd.setOpaque(false);
		p.add(txtCustomCmd);
		y += h;
		y += getScaledInt(20);

		h = getScaledInt(30);
		chkExecAntivir = createCheckBox("EXE_ANTI_VIR");
		chkExecAntivir.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkExecAntivir);
		y += h;
		y += getScaledInt(5);

		h = getScaledInt(30);
		JLabel lbl12 = new JLabel(StringResource.get("ANTIVIR_CMD"));
		lbl12.setForeground(Color.WHITE);
		lbl12.setFont(FontResource.getNormalFont());
		lbl12.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl12);
		y += h;

		h = getScaledInt(25);
		txtAntivirCmd = new JTextField();
		txtAntivirCmd.setBounds(getScaledInt(15), y,
				getScaledInt(350) - getScaledInt(30) - getScaledInt(10) - getScaledInt(100), h);
		txtAntivirCmd.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtAntivirCmd.setForeground(Color.WHITE);
		txtAntivirCmd.setOpaque(false);
		p.add(txtAntivirCmd);
		JButton btnBrowse = createButton2("BTN_BROWSE");
		btnBrowse.setName("BROWSE_ANTIVIR");
		btnBrowse.setBackground(ColorResource.getDarkBtnColor());
		btnBrowse.setFont(FontResource.getNormalFont());
		btnBrowse.setBounds(
				getScaledInt(15) + getScaledInt(350) - getScaledInt(30) - getScaledInt(110) + getScaledInt(10), y,
				getScaledInt(90), h);
		p.add(btnBrowse);
		y += h;

		h = getScaledInt(30);
		JLabel lbl1 = new JLabel(StringResource.get("ANTIVIR_ARGS"));
		lbl1.setForeground(Color.WHITE);
		lbl1.setFont(FontResource.getNormalFont());
		lbl1.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		p.add(lbl1);
		y += h;

		h = getScaledInt(25);
		txtAntivirArgs = new JTextField();
		txtAntivirArgs.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30) - getScaledInt(10), h);
		txtAntivirArgs.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtAntivirArgs.setForeground(Color.WHITE);
		txtAntivirArgs.setOpaque(false);
		p.add(txtAntivirArgs);
		y += h;
		y += getScaledInt(20);
		h = getScaledInt(30);
		chkAutoStart = createCheckBox("AUTO_START");
		chkAutoStart.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkAutoStart);
		y += h;
		h = getScaledInt(30);
		chkShowTray = createCheckBox("LBL_SHOW_TRAY");
		chkShowTray.setBounds(getScaledInt(15), y, getScaledInt(350), h);
		p.add(chkShowTray);
		y += h;
		y += getScaledInt(50);
		if (XDMUtils.detectOS() == XDMUtils.LINUX) {
			chkShowTray.setVisible(true);
		} else {
			chkShowTray.setVisible(false);
		}

		p.setPreferredSize(new Dimension(getScaledInt(350), y));
		return p;
	}

	private void loadAdvSettings() {
		Config config = Config.getInstance();
		chkHaltAfterFinish.setSelected(config.isAutoShutdown());
		chkKeepAwake.setSelected(config.isKeepAwake());
		chkExecCmd.setSelected(config.isExecCmd());
		chkExecAntivir.setSelected(config.isExecAntivir());
		chkAutoStart.setSelected(XDMUtils.isAlreadyAutoStart());
		chkShowTray.setSelected(!config.isHideTray());
		if (!StringUtils.isNullOrEmptyOrBlank(config.getCustomCmd()))
			txtCustomCmd.setText(config.getCustomCmd());
		if (!StringUtils.isNullOrEmptyOrBlank(config.getAntivirCmd()))
			txtAntivirArgs.setText(config.getAntivirCmd());
		if (!StringUtils.isNullOrEmptyOrBlank(config.getAntivirExe()))
			txtAntivirCmd.setText(config.getAntivirExe());
	}

	private void saveAdvSettings() {
		Config config = Config.getInstance();
		config.setAutoShutdown(chkHaltAfterFinish.isSelected());
		config.setKeepAwake(chkKeepAwake.isSelected());
		config.setExecCmd(chkExecCmd.isSelected());
		config.setExecAntivir(chkExecAntivir.isSelected());
		config.setHideTray(!chkShowTray.isSelected());
		if (chkAutoStart.isSelected()) {
			XDMUtils.addToStartup();
		} else {
			XDMUtils.removeFromStartup();
		}
		// config.setAutoStart(chkAutoStart.isSelected());
		String customCmd = txtCustomCmd.getText();
		config.setCustomCmd(customCmd);
		String antivirExec = txtAntivirCmd.getText();
		config.setAntivirExe(antivirExec);
		String antivirCmd = txtAntivirArgs.getText();
		config.setAntivirCmd(antivirCmd);
	}

	private JRadioButton createRadioButton(String name, Font font) {
		JRadioButton chk = new JRadioButton(StringResource.get(name));
		chk.setIcon(ImageResource.get("unchecked.png"));
		chk.setSelectedIcon(ImageResource.get("checked.png"));
		chk.setOpaque(false);
		chk.setFocusPainted(false);
		chk.setForeground(Color.WHITE);
		chk.setFont(font);
		return chk;
	}

	@Override
	public void valueChanged(ListSelectionEvent e) {
		if (e.getSource() == qList) {
			updateSchedulerFields();
		}
	}

	private void updateSchedulerFields() {
		int index = qList.getSelectedIndex();
		if (index < 0)
			return;

		DownloadQueue q = queueModel.getElementAt(index);
		txtQueueName.setText(q.getName());

		loadQueuedItems(q);

		btnQMoveTo.setEnabled(QueueManager.getInstance().getQueueList().size() > 1 && q.getQueuedItems().size() > 0);

		Date startTime = DateTimeUtils.addTimePart(q.getStartTime());
		Date endTime = DateTimeUtils.addTimePart(q.getEndTime());

		if (startTime == null) {
			endTime = null;
		}

		chkQStart.setSelected(startTime != null);
		chkQStop.setSelected(endTime != null);

		if (startTime == null) {
			startTime = DateTimeUtils.getDefaultStart();
		}
		if (endTime == null || endTime.before(startTime)) {
			endTime = DateTimeUtils.getDefaultEnd();
		}

		spinnerDateModel1.setValue(startTime);
		spinnerDateModel2.setValue(endTime);

		for (int i = 1; i <= 7; i++) {
			JCheckBox chk = chkDays[i - 1];
			chk.setSelected(false);
		}

		int mask = 0x01;
		if (q.isPeriodic()) {
			for (int i = 1; i <= 7; i++) {
				JCheckBox chk = chkDays[i - 1];
				chk.setSelected((q.getDayMask() & mask) == mask);
				mask = mask << 1;
			}
			radPeriodic.setSelected(true);
		} else {
			Date execDate = q.getExecDate();
			if (execDate == null || execDate.before(new Date())) {
				execDate = new Date();
			}
			radOnetime.setSelected(true);
			spinnerDateModel3.setValue(execDate);
		}
		enableSchedulerFields();
	}

	private void loadQueuedItems(DownloadQueue q) {
		queuedItemsModel.clear();
		ArrayList<String> idList = q.getQueuedItems();
		for (int i = 0; i < idList.size(); i++) {
			String id = idList.get(i);
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null || ent.getState() == XDMConstants.FINISHED) {
				continue;
			}
			queuedItemsModel.addElement(id);
		}
		if (idList.size() > 0) {
			qItemsList.setSelectedIndex(0);
		}
	}

	private void enableSchedulerFields() {
		radOnetime.setEnabled(chkQStart.isSelected());
		radPeriodic.setEnabled(chkQStart.isSelected());
		spExecDate.setEnabled(chkQStart.isSelected());
		spStartTime.setEnabled(chkQStart.isSelected());
		spEndTime.setEnabled(chkQStart.isSelected());
		chkQStop.setEnabled(chkQStart.isSelected());
		spExecDate.setEnabled(radOnetime.isSelected() && chkQStart.isSelected());

		for (int i = 0; i < 7; i++) {
			chkDays[i].setEnabled(chkQStart.isSelected() && radPeriodic.isSelected());
		}

	}

	private void saveQueue() {
		int index = qList.getSelectedIndex();
		if (index < 0)
			return;
		DownloadQueue q = queueModel.getElementAt(index);
		if (txtQueueName.getText().length() > 0) {
			q.setName(txtQueueName.getText());
		}
		if (chkQStart.isSelected()) {

			q.setStartTime(DateTimeUtils.getTimePart(spinnerDateModel1.getDate()));
			System.out.println(spinnerDateModel1.getDate());
			if (chkQStop.isSelected()) {
				q.setEndTime(DateTimeUtils.getTimePart(spinnerDateModel2.getDate()));
			} else {
				q.setEndTime(-1);
			}
			if (radOnetime.isSelected()) {
				q.setPeriodic(false);
				q.setExecDate(spinnerDateModel3.getDate());
				q.setDayMask(0);
			} else {
				q.setPeriodic(true);
				q.setExecDate(null);
				int dayMask = 0;
				int mask = 0x01;
				for (int i = 1; i <= 7; i++) {
					JCheckBox chk = chkDays[i - 1];
					if (chk.isSelected()) {
						dayMask |= mask;
					}
					mask = mask << 1;
				}
				q.setDayMask(dayMask);
			}
		} else {
			q.setStartTime(-1);
		}

		ArrayList<String> newOrder = new ArrayList<String>(queuedItemsModel.size());

		for (int i = 0; i < queuedItemsModel.size(); i++) {
			newOrder.add(queuedItemsModel.get(i));
		}

		q.reorderItems(newOrder);

		QueueManager.getInstance().saveQueues();
	}

	private void queueMoveUp() {
		int index = qItemsList.getSelectedIndex();
		if (index < 0) {
			return;
		}
		if (index == 0) {
			return;
		}
		String prevId = queuedItemsModel.get(index - 1);
		String nextId = queuedItemsModel.get(index);

		queuedItemsModel.set(index, prevId);
		queuedItemsModel.set(index - 1, nextId);

		qItemsList.setSelectedIndex(index - 1);
		qItemsList.ensureIndexIsVisible(index - 1);
	}

	private void queueMoveDown() {
		int index = qItemsList.getSelectedIndex();
		if (index < 0)
			return;
		if (index == queuedItemsModel.size() - 1) {
			return;
		}
		String prevId = queuedItemsModel.get(index);
		String nextId = queuedItemsModel.get(index + 1);

		queuedItemsModel.set(index + 1, prevId);
		queuedItemsModel.set(index, nextId);

		qItemsList.setSelectedIndex(index + 1);
		qItemsList.ensureIndexIsVisible(index + 1);
	}

	private void removeQueue() {
		int index = qList.getSelectedIndex();
		if (index < 1)
			return;
		DownloadQueue q = queueModel.get(index);
		QueueManager.getInstance().removeQueue(q.getQueueId());
		loadSchedulerSettings(index < QueueManager.getInstance().getQueueList().size() ? index : index - 1);
	}

	private void createNewQueue() {
		int index = QueueManager.getInstance().getQueueList().size();
		QueueManager.getInstance().createNewQueue();
		int count = QueueManager.getInstance().getQueueList().size();
		loadSchedulerSettings(index < count ? index : 0);
	}

	private void showMoveQPopup(JButton btn) {
		int index = qList.getSelectedIndex();
		if (index < 0) {
			return;
		}
		DownloadQueue q = queueModel.get(index);
		String qid = q.getQueueId();
		if (qid == null)
			return;
		JPopupMenu popupMenu = new JPopupMenu();
		for (int i = 0; i < QueueManager.getInstance().getQueueList().size(); i++) {
			DownloadQueue tq = QueueManager.getInstance().getQueueList().get(i);
			if (qid.equals(tq.getQueueId())) {
				continue;
			}
			JMenuItem item = new JMenuItem(tq.getName());
			item.setName("Q_MOVE_TO:" + tq.getQueueId());
			item.addActionListener(this);
			item.setForeground(Color.WHITE);
			item.setFont(FontResource.getNormalFont());
			popupMenu.add(item);
		}
		popupMenu.setInvoker(btn);
		popupMenu.show(btn, 0, btn.getHeight());
	}

	private void saveOverviewSettings() {
		Config config = Config.getInstance();
		config.setShowDownloadWindow(chkPrgWnd.isSelected());
		config.setShowDownloadCompleteWindow(chkEndWnd.isSelected());
		config.setDuplicateAction(chkOverwriteExisting.isSelected() ? 1 : 0);
		String text = cmbMax.getSelectedItem() + "";
		if ("N/A".equals(text)) {
			config.setMaxDownloads(0);
		} else {
			config.setMaxDownloads(Integer.parseInt(text));
		}
		// config.setDuplicateAction(cmbDupAction.getSelectedIndex());
		config.setDownloadFolder(txtDefFolder.getText());
		config.setTemporaryFolder(txtTempFolder.getText());
		config.setNoTransparency(chkNoTransparency.isSelected());
		config.setShowVideoListOnlyInBrowser(chkVidBrowserOnly.isSelected());
		config.save();
	}

	private void saveMonitoringSettings() {
		Config config = Config.getInstance();
		config.setFileExts(XDMUtils.appendStr2Array(txtFileTyp.getText()));
		config.setVidExts(XDMUtils.appendStr2Array(txtVidType.getText()));
		config.setBlockedHosts(XDMUtils.appendStr2Array(txtBlockedHosts.getText()));
		config.setShowVideoNotification(chkVidPan.isSelected());
		config.setMonitorClipboard(chkMonitorClipboard.isSelected());
		if (config.isMonitorClipboard()) {
			ClipboardMonitor.getInstance().startMonitoring();
		} else {
			ClipboardMonitor.getInstance().stopMonitoring();
		}
		int index = cmbMinVidSize.getSelectedIndex();
		if (index >= 0) {
			config.setMinVidSize(sizeArr[index]);
		}
		config.setDownloadAutoStart(chkDwnAuto.isSelected());
		config.setFetchTs(chkGetTs.isSelected());
		config.save();
	}

	private void saveNetworkSettings() {
		Config config = Config.getInstance();
		config.setNetworkTimeout(
				cmbTimeout.getSelectedItem().equals("N/A") ? 0 : Integer.parseInt(cmbTimeout.getSelectedItem() + ""));
		config.setMaxSegments(Integer.parseInt(cmbSeg.getSelectedItem() + ""));
		String val = cmbTcp.getSelectedItem() + "";
		int ival = 0;
		try {
			ival = Integer.parseInt(val);
		} catch (Exception e) {
		}
		config.setTcpWindowSize(ival);
		// try {
		// int speedLimit = Integer.parseInt(txtSpeedLimit.getText());
		// config.setSpeedLimit(speedLimit);
		// } catch (Exception e) {
		// }

		int proxyMode = 0;
		if (chkUsePac.isSelected()) {
			proxyMode = 1;
		} else if (chkUseProxy.isSelected()) {
			proxyMode = 2;
		} else if (chkUseSocks.isSelected()) {
			proxyMode = 3;
		}

		config.setProxyMode(proxyMode);

		config.setProxyPac(txtPACUrl.getText());
		String proxyText = txtProxyHostnPort.getText();
		if (proxyText.length() > 0) {
			String host = null;
			int port = 80;
			if (proxyText.indexOf(":") != -1) {
				String[] arr = proxyText.split(":");
				host = arr[0];
				try {
					port = Integer.parseInt(arr[1]);
				} catch (Exception e) {
					host = null;
					port = 0;
				}
			} else {
				host = proxyText;
			}
			if (port > 0 && host != null && host.length() > 0) {
				config.setProxyHost(host);
				config.setProxyPort(port);
			}
		}

		String socksText = txtSocksHostnPort.getText();
		if (socksText.length() > 0) {
			String host = null;
			int port = 1080;
			if (socksText.indexOf(":") != -1) {
				String[] arr = socksText.split(":");
				host = arr[0];
				try {
					port = Integer.parseInt(arr[1]);
				} catch (Exception e) {
					host = null;
					port = 0;
				}
			} else {
				host = socksText;
			}
			if (port > 0 && host != null && host.length() > 0) {
				config.setSocksHost(host);
				config.setSocksPort(port);
			}
		}

		config.setProxyUser(txtProxyUser.getText());
		config.setProxyPass(txtProxyPass.getText());
	}
}

class PasswordItem {
	String host;
	String user;
	String password;

	@Override
	public String toString() {
		return host + "[" + user + "]";
	}
}
