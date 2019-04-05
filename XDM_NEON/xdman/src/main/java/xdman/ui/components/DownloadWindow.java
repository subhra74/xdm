package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import xdman.Config;
import xdman.DownloadEntry;
import xdman.DownloadWindowListener;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.downloaders.Downloader;
import xdman.downloaders.SegmentDetails;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.FormatUtilities;
import xdman.util.Logger;

public class DownloadWindow extends JFrame implements ActionListener {
	private static final long serialVersionUID = -5523541940635914890L;
	private String id;
	private CircleProgressBar prgCircle;
	private SegmentPanel segProgress;
	private int errCode, reason;
	//private String errMsg;
	private JLabel titleLbl;
	private JLabel lblSpeed;
	private JLabel lblStat;
	private JLabel lblDet;
	private JLabel lblETA;
	private JPanel titlePanel;
	private JTextArea txtError;
	private JPanel panel;
	private JButton closeBtn, minBtn;
	private DownloadWindowListener listener;
	private JPopupMenu pop;
	private CustomButton btnMore;

	public DownloadWindow(String id, DownloadWindowListener listener) {
		this.id = id;
		this.listener = listener;
		init();
	}

	public void close(int code, int error) {
		this.errCode = error;
		this.reason = code;
		this.listener = null;
		EventQueue.invokeLater(new Runnable() {
			@Override
			public void run() {
				if (reason == XDMConstants.FAILED) {
					createP2();
					showErrorMsg(errCode);
				} else {
					dispose();
				}
			}
		});
	}

	public void update(Downloader d, String file) {
		titleLbl.setText(file);
		if (d.getProgress() > 0) {
			setTitle("[" + d.getProgress() + "%]" + file);
		} else {
			setTitle(file);
		}
		String statTxt = "";
		if (d.isConverting()) {
			statTxt = StringResource.get("TITLE_CONVERT");
		} else if (d.isAssembling()) {
			statTxt = StringResource.get("STAT_ASSEMBLING");
		} else {
			statTxt = StringResource.get("STAT_DOWNLOADING");
		}
		lblStat.setText(statTxt);
		// StringBuilder sb = new StringBuilder();
		// sb.append((d.isAssembling() ? StringResource.get("STAT_ASSEMBLING")
		// : StringResource.get("DWN_DOWNLOAD")));
		// sb.append(" ");
		// sb.append(FormatUtilities.formatSize(d.getDownloaded()));
		// sb.append(" ");
		// sb.append(d.getType()==XDMConstants.HTTP?)

		lblDet.setText((d.isAssembling() ? StringResource.get("STAT_ASSEMBLING") : StringResource.get("DWN_DOWNLOAD"))
				+ " " + FormatUtilities.formatSize(d.getDownloaded()) + " "
				+ ((d.getType() == XDMConstants.HTTP || d.getType() == XDMConstants.DASH)
						? "/ " + FormatUtilities.formatSize(d.getSize())
						: "( " + d.getProgress() + " % )"));
		lblSpeed.setText(FormatUtilities.formatSize(d.getDownloadSpeed()) + "/s");
		lblETA.setText("ETA " + d.getEta());
		prgCircle.setValue(d.getProgress());
		SegmentDetails segDet = d.getSegmentDetails();
		long sz = ((d.getType() == XDMConstants.HTTP || d.getType() == XDMConstants.FTP
				|| d.getType() == XDMConstants.DASH) ? d.getSize() : 100);
		segProgress.setValues(segDet, sz);
	}

	private void createP2() {

		remove(prgCircle);
		remove(lblSpeed);
		remove(lblStat);
		remove(segProgress);
		remove(lblDet);
		remove(lblETA);
		remove(this.panel);

		titlePanel.remove(closeBtn);
		titlePanel.remove(minBtn);

		JPanel p2 = new JPanel(null);
		p2.setBounds(0, getScaledInt(60), getScaledInt(350), getScaledInt(190));
		p2.setBackground(ColorResource.getDarkestBgColor());

		txtError = new JTextArea();//this.errMsg);
		txtError.setFont(FontResource.getBigFont());
		txtError.setEditable(false);
		txtError.setCaretPosition(0);
		txtError.setWrapStyleWord(true);
		txtError.setLineWrap(true);
		txtError.setBackground(ColorResource.getDarkestBgColor());
		txtError.setForeground(Color.WHITE);

		JScrollPane jsp = new JScrollPane(txtError);
		jsp.setBounds(getScaledInt(25), getScaledInt(20), getScaledInt(300), getScaledInt(100));
		jsp.setBorder(null);

		CustomButton exitBtn = new CustomButton();
		exitBtn.setText(StringResource.get("MSG_OK"));
		applyStyle(exitBtn);
		exitBtn.setBounds(0, 1, getScaledInt(350), getScaledInt(50));
		exitBtn.setName("EXIT");

		JPanel panel2 = new JPanel(null);
		panel2.setBounds(0, getScaledInt(140), getScaledInt(350), getScaledInt(50));
		panel2.setBackground(Color.DARK_GRAY);
		panel2.add(exitBtn);

		p2.add(jsp);
		p2.add(panel2);

		add(p2);

		titleLbl.setText(StringResource.get("MSG_FAILED"));

		invalidate();
		repaint();
	}

	private void init() {
		setUndecorated(true);
		try {
			if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
					.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
				if (!Config.getInstance().isNoTransparency()) {
					setOpacity(0.85f);
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		}

		setTitle("Downloading...");
		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(350), getScaledInt(250));
		setLocationRelativeTo(null);
		setResizable(false);

		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(350), getScaledInt(50));

		closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(320), getScaledInt(5), getScaledInt(24), getScaledInt(24));
		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("PAUSE");
		closeBtn.addActionListener(this);

		minBtn = new CustomButton();
		minBtn.setBounds(getScaledInt(296), getScaledInt(5), getScaledInt(24), getScaledInt(24));
		minBtn.setIcon(ImageResource.get("title_min.png"));
		minBtn.setBackground(ColorResource.getDarkestBgColor());
		minBtn.setBorderPainted(false);
		minBtn.setFocusPainted(false);
		minBtn.setName("MIN");
		minBtn.addActionListener(this);

		titleLbl = new JLabel(StringResource.get("DWN_TITLE"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(250), getScaledInt(30));

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(400), 2);
		lineLbl.setOpaque(true);

		prgCircle = new CircleProgressBar();
		prgCircle.setValue(0);

		prgCircle.setBounds(getScaledInt(20), getScaledInt(80), getScaledInt(72), getScaledInt(72));

		titlePanel.add(titleLbl);
		titlePanel.add(minBtn);
		titlePanel.add(closeBtn);

		lblSpeed = new JLabel("---");
		lblSpeed.setHorizontalAlignment(JLabel.CENTER);
		lblSpeed.setBounds(getScaledInt(15), getScaledInt(160), getScaledInt(80), getScaledInt(25));
		lblSpeed.setForeground(Color.WHITE);

		lblStat = new JLabel(StringResource.get("DWN_TITLE"));
		lblStat.setBounds(getScaledInt(120), getScaledInt(85), getScaledInt(200), getScaledInt(25));
		lblStat.setForeground(Color.WHITE);

		segProgress = new SegmentPanel();
		segProgress.setBounds(getScaledInt(120), getScaledInt(115), getScaledInt(200), getScaledInt(5));

		lblDet = new JLabel(StringResource.get("DWN_PLACEHOLDER"));
		lblDet.setBounds(getScaledInt(120), getScaledInt(125), getScaledInt(200), getScaledInt(25));
		lblDet.setForeground(Color.WHITE);

		lblETA = new JLabel("---");
		lblETA.setBounds(getScaledInt(120), getScaledInt(150), getScaledInt(200), getScaledInt(25));
		lblETA.setForeground(Color.WHITE);

		panel = new JPanel(null);
		panel.setBounds(0, getScaledInt(200), getScaledInt(350), getScaledInt(50));
		panel.setBackground(Color.DARK_GRAY);

		btnMore = new CustomButton(StringResource.get("ND_MORE"));
		CustomButton btnDN = new CustomButton(StringResource.get("DWN_PREVIEW"));
		CustomButton btnCN = new CustomButton(StringResource.get("MENU_PAUSE"));

		btnMore.setBounds(0, 1, getScaledInt(100), getScaledInt(50));
		btnMore.setName("MORE");
		applyStyle(btnMore);

		btnDN.setBounds(getScaledInt(101), 1, getScaledInt(144), getScaledInt(50));
		btnDN.setName("PREVIEW");
		applyStyle(btnDN);

		btnCN.setBounds(getScaledInt(246), 1, getScaledInt(104), getScaledInt(50));
		btnCN.setName("PAUSE");
		applyStyle(btnCN);

		add(titlePanel);
		add(lineLbl);
		add(prgCircle);
		add(lblSpeed);
		add(lblStat);
		add(segProgress);
		add(lblDet);
		add(lblETA);

		panel.add(btnMore);
		panel.add(btnDN);
		panel.add(btnCN);

		add(panel);

		pop = new JPopupMenu();
		pop.setBackground(ColorResource.getDarkerBgColor());

		JMenuItem hd = new JMenuItem(StringResource.get("DWN_HIDE"));
		hd.setName("BACKGROUND");
		hd.setForeground(Color.WHITE);
		hd.addActionListener(this);
		pop.add(hd);

		JMenuItem sv = new JMenuItem(StringResource.get("CTX_SAVE_AS"));
		sv.setName("SAVE_AS");
		sv.setForeground(Color.WHITE);
		sv.addActionListener(this);
		pop.add(sv);

		pop.setInvoker(btnMore);

	}

	void applyStyle(CustomButton btn) {
		btn.addActionListener(this);
		btn.setBackground(ColorResource.getDarkestBgColor());
		btn.setForeground(Color.WHITE);
		btn.setPressedBackground(ColorResource.getDarkerBgColor());
		btn.setFont(FontResource.getBigFont());
		btn.setBorderPainted(false);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setFocusPainted(false);
		btn.setFocusPainted(false);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String name = ((JComponent) e.getSource()).getName();
		if ("PAUSE".equals(name)) {
			pause();
		} else if ("BACKGROUND".equals(name)) {
			hideWnd();
		} else if ("PREVIEW".equals(name)) {
			openPreviewFolder();
		} else if ("MIN".equals(name)) {
			this.setExtendedState(this.getExtendedState() | JFrame.ICONIFIED);
		} else if ("EXIT".equals(name)) {
			dispose();
		} else if ("MORE".equals(name)) {
			pop.show(btnMore, 0, btnMore.getHeight());
		} else if ("SAVE_AS".equals(name)) {
			changeFile(id);
		}
	}

	private void pause() {
		if (listener != null) {
			listener.pauseDownload(id);
		}
	}

	private void hideWnd() {
		if (listener != null) {
			listener.hidePrgWnd(id);
		}
	}

	private void openPreviewFolder() {
		XDMApp.getInstance().openPreview(id);
	}

	private void showErrorMsg(int code) {
		switch (code) {
		case XDMConstants.ERR_CONN_FAILED:
			txtError.setText(StringResource.get("ERR_CONN_FAILED"));
			return;
		case XDMConstants.ERR_SESSION_FAILED:
			txtError.setText(StringResource.get("ERR_SESSION_FAILED"));
			return;
		case XDMConstants.ERR_NO_RESUME:
			txtError.setText(StringResource.get("ERR_NO_RESUME"));
			return;
		case XDMConstants.ERR_INVALID_RESP:
			txtError.setText(StringResource.get("ERR_INVALID_RESP"));
			return;
		case XDMConstants.ERR_ASM_FAILED:
			txtError.setText(StringResource.get("ERR_ASM_FAILED"));
			return;
		case XDMConstants.RESUME_FAILED:
			txtError.setText(StringResource.get("RESUME_FAILED"));
			return;
		case XDMConstants.DISK_FAIURE:
			txtError.setText(StringResource.get("ERR_DISK_FAILED"));
			return;
		default:
			txtError.setText(StringResource.get("ERR_INTERNAL"));
			return;
		}
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

}
