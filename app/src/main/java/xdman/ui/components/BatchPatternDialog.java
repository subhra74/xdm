package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerListModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.LineBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import xdman.Config;
import xdman.CredentialManager;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

public class BatchPatternDialog extends JFrame implements ActionListener, DocumentListener, ChangeListener {
	/**
	 * 
	 */
	public BatchPatternDialog() {
		initUI();
	}

	private static final long serialVersionUID = 8254530542382624878L;
	JTextField txtUrl;
	JRadioButton radNum, radAlpha;
	JCheckBox chkUseAuth;
	JTextField txtUser, txtPass;
	JTextField txtFile1, txtFile2, txtFileN;
	JButton btnOK, btnClose;
	JSpinner spFrom, spTo;
	SpinnerNumberModel spFromModelNum, spToModelNum;
	SpinnerListModel spFromModelAlpha, spToModelAlpha;
	List<String> urls;

	private void initUI() {
		this.urls = new ArrayList<>();
		setUndecorated(true);

		try {
			if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
					.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
				if (!Config.getInstance().isNoTransparency())
					setOpacity(0.85f);
			}
		} catch (Exception e) {
			Logger.log(e);
		}

		spFromModelNum = new SpinnerNumberModel(Integer.valueOf(0), Integer.valueOf(0), Integer.valueOf(9999),
				Integer.valueOf(1));
		spToModelNum = new SpinnerNumberModel(Integer.valueOf(100), Integer.valueOf(0), Integer.valueOf(9999),
				Integer.valueOf(1));

		String chars[] = new String[52];
		int index = 0;
		for (char i = 'a'; i <= 'z'; i++) {
			chars[index++] = i + "";
		}
		for (char i = 'A'; i <= 'Z'; i++) {
			chars[index++] = i + "";
		}

		spFromModelAlpha = new SpinnerListModel(chars);
		spToModelAlpha = new SpinnerListModel(chars);

		setTitle(StringResource.get("MENU_BATCH_DOWNLOAD"));
		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(500), getScaledInt(420));
		setLocationRelativeTo(null);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getWidth(), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getWidth() - getScaledInt(35), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("MENU_BATCH_DOWNLOAD"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getWidth(), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		add(titlePanel);

		int y = getScaledInt(55);
		int h = getScaledInt(50);
		y += getScaledInt(10);
		JTextArea lbldesc = new JTextArea();
		lbldesc.setOpaque(false);
		lbldesc.setWrapStyleWord(true);
		lbldesc.setLineWrap(true);
		lbldesc.setEditable(false);
		lbldesc.setForeground(Color.WHITE);
		lbldesc.setText(StringResource.get("LBL_BATCH_DESC"));
		lbldesc.setFont(FontResource.getNormalFont());
		lbldesc.setBounds(getScaledInt(15), y, getWidth() - getScaledInt(30), h);
		add(lbldesc);

		y += h;
		h = getScaledInt(25);

		JLabel lblUrl = new JLabel(StringResource.get("ND_ADDRESS"));
		lblUrl.setBounds(getScaledInt(15), y, getScaledInt(100), h);
		add(lblUrl);
		txtUrl = new JTextField();
		txtUrl.setBounds(getScaledInt(120), y, getWidth() - getScaledInt(135), h);
		txtUrl.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtUrl.setForeground(Color.WHITE);
		txtUrl.setOpaque(false);
		txtUrl.getDocument().addDocumentListener(this);
		add(txtUrl);
		y += h;
		y += getScaledInt(10);

		JLabel lblReplace = new JLabel(StringResource.get("LBL_BATCH_ASTERISK"));
		lblReplace.setBounds(getScaledInt(15), y, getScaledInt(150), h);
		add(lblReplace);

		ButtonGroup radioGroup = new ButtonGroup();

		radNum = createRadioButton("LBL_BATCH_NUM", FontResource.getNormalFont());
		radNum.setName("RAD_NUM");
		radNum.addActionListener(this);
		radNum.setBounds(getScaledInt(160), y, getScaledInt(120), h);
		add(radNum);
		radioGroup.add(radNum);

		radAlpha = createRadioButton("LBL_BATCH_LETTER", FontResource.getNormalFont());
		radAlpha.setName("RAD_LETTER");
		radAlpha.addActionListener(this);
		radAlpha.setBounds(getScaledInt(280), y, getScaledInt(120), h);
		add(radAlpha);
		radioGroup.add(radAlpha);

		y += h;
		y += getScaledInt(10);

		int x = getScaledInt(15);
		int w = getScaledInt(50);
		JLabel lblFrom = new JLabel(StringResource.get("LBL_BATCH_FROM"));
		lblFrom.setBounds(x, y, w, h);
		lblFrom.setHorizontalAlignment(JLabel.RIGHT);
		add(lblFrom);
		x += w;
		x += getScaledInt(5);

		w = getScaledInt(50);
		spFrom = new JSpinner();
		spFrom.addChangeListener(this);
		spFrom.setBounds(x, y, w, h);
		transparentSpinner(spFrom);
		add(spFrom);
		x += w;
		x += getScaledInt(10);

		w = getScaledInt(30);
		JLabel lblTo = new JLabel(StringResource.get("LBL_BATCH_TO"));
		lblTo.setHorizontalAlignment(JLabel.RIGHT);
		lblTo.setBounds(x, y, w, h);
		add(lblTo);
		x += w;
		x += getScaledInt(5);

		w = getScaledInt(50);
		spTo = new JSpinner();
		spTo.setBackground(getBackground());
		spTo.addChangeListener(this);
		spTo.setOpaque(false);
		transparentSpinner(spTo);
		spTo.setBounds(x, y, w, h);
		add(spTo);
		x += w;
		x += getScaledInt(10);

		y += h;
		y += getScaledInt(10);

		chkUseAuth = new JCheckBox(StringResource.get("LBL_BATCH_CHK_AUTH"));
		chkUseAuth.setName("LBL_USER_PASS");
		chkUseAuth.setBackground(ColorResource.getDarkestBgColor());
		chkUseAuth.setIcon(ImageResource.get("unchecked.png"));
		chkUseAuth.setSelectedIcon(ImageResource.get("checked.png"));
		chkUseAuth.addActionListener(this);
		chkUseAuth.setForeground(Color.WHITE);
		chkUseAuth.setFocusPainted(false);
		chkUseAuth.setBounds(getScaledInt(15), y, getWidth(), h);
		add(chkUseAuth);

		y += h;

		JLabel lblUser = new JLabel(StringResource.get("DESC_USER"));
		lblUser.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		add(lblUser);

		txtUser = new JTextField();
		txtUser.setBounds(getScaledInt(95), y, getScaledInt(100), h);
		txtUser.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtUser.setForeground(Color.WHITE);
		txtUser.setOpaque(false);
		add(txtUser);

		JLabel lblPass = new JLabel(StringResource.get("DESC_PASS"));
		lblPass.setBounds(getScaledInt(210), y, getScaledInt(80), h);
		add(lblPass);

		txtPass = new JPasswordField();
		txtPass.setBounds(getScaledInt(300), y, getScaledInt(100), h);
		txtPass.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtPass.setForeground(Color.WHITE);
		txtPass.setOpaque(false);
		add(txtPass);
		y += h;
		y += getScaledInt(10);

		JLabel lblFile1 = new JLabel(StringResource.get("LBL_BATCH_FILE1"));
		lblFile1.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		add(lblFile1);

		txtFile1 = new JTextField();
		txtFile1.setBounds(getScaledInt(90), y, getWidth() - getScaledInt(105), h);
		txtFile1.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtFile1.setForeground(Color.WHITE);
		txtFile1.setEditable(false);
		txtFile1.setOpaque(false);
		add(txtFile1);

		y += h;
		y += getScaledInt(5);

		JLabel lblFile2 = new JLabel(StringResource.get("LBL_BATCH_FILE2"));
		lblFile2.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		add(lblFile2);

		txtFile2 = new JTextField();
		txtFile2.setEditable(false);
		txtFile2.setBounds(getScaledInt(90), y, getWidth() - getScaledInt(105), h);
		txtFile2.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtFile2.setForeground(Color.WHITE);
		txtFile2.setOpaque(false);
		add(txtFile2);

		y += h;
		y += getScaledInt(5);

		JLabel lblFileN = new JLabel(StringResource.get("LBL_BATCH_FILEN"));
		lblFileN.setBounds(getScaledInt(15), y, getScaledInt(80), h);
		add(lblFileN);

		txtFileN = new JTextField();
		txtFileN.setEditable(false);
		txtFileN.setBounds(getScaledInt(90), y, getWidth() - getScaledInt(105), h);
		txtFileN.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtFileN.setForeground(Color.WHITE);
		txtFileN.setOpaque(false);
		add(txtFileN);

		y += h;
		y += getScaledInt(20);

		btnOK = createButton("MSG_OK");
		btnOK.setBounds(getWidth() - getScaledInt(240), y, getScaledInt(100), h);
		btnOK.setName("OK");
		add(btnOK);

		btnClose = createButton("ND_CANCEL");
		btnClose.setBounds(getWidth() - getScaledInt(115), y, getScaledInt(100), h);
		btnClose.setName("CLOSE");
		add(btnClose);

		radNum.setSelected(true);
		spFrom.setModel(spFromModelNum);
		spTo.setModel(spToModelNum);
		transparentSpinner(spTo);
		transparentSpinner(spFrom);

		addWindowFocusListener(new WindowFocusListener() {

			@Override
			public void windowLostFocus(WindowEvent e) {
				// TODO Auto-generated method stub

			}

			@Override
			public void windowGainedFocus(WindowEvent e) {
				txtUrl.requestFocus();
			}
		});
	}

	private void transparentSpinner(JSpinner spTo2) {
		JComponent c = spTo2.getEditor();
		for (int i = 0; i < c.getComponentCount(); i++) {
			Component ct = c.getComponent(i);
			if (ct instanceof JTextField) {
				ct.setForeground(Color.WHITE);
				ct.setBackground(ColorResource.getDarkBtnColor());
			}
		}
		spTo2.setForeground(Color.WHITE);
		spTo2.setBackground(ColorResource.getDarkBgColor());
		spTo2.setBorder(null);

	}

	private JButton createButton(String name) {
		JButton btn = new CustomButton(StringResource.get(name));
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		btn.addActionListener(this);
		return btn;
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
	public void actionPerformed(ActionEvent e) {
		String name = (e.getSource() instanceof JComponent) ? ((JComponent) e.getSource()).getName() : "";
		if ("RAD_NUM".equals(name)) {
			spFrom.setModel(spFromModelNum);
			spTo.setModel(spToModelNum);
			transparentSpinner(spTo);
			transparentSpinner(spFrom);
			makeUrls();
		} else if ("RAD_LETTER".equals(name)) {
			spFrom.setModel(spFromModelAlpha);
			spTo.setModel(spToModelAlpha);
			spTo.setValue("z");
			transparentSpinner(spTo);
			transparentSpinner(spFrom);
			makeUrls();
		} else if ("OK".equals(name)) {
			try {
				if (chkUseAuth.isSelected()) {
					String user = txtUser.getText();
					String pass = txtPass.getText();
					String host = new URL(txtUrl.getText()).getHost();
					CredentialManager.getInstance().addCredentialForHost(host,
							new PasswordAuthentication(user, pass.toCharArray()));
				}
				if (urls.size() > 0) {
					dispose();
					new BatchDownloadWnd(XDMUtils.toMetadata(urls)).setVisible(true);
				}
			} catch (Exception e2) {
				Logger.log(e2);
			}

		} else if ("CLOSE".equals(name)) {
			dispose();
		}
	}

	private void makeUrls() {
		this.urls.clear();
		this.txtFile1.setText("");
		this.txtFile2.setText("");
		this.txtFileN.setText("");
		try {
			new URL(this.txtUrl.getText());
			this.urls.addAll(generateUrls());
			if (this.urls.size() > 0) {
				txtFile1.setText(this.urls.get(0));
			}
			if (this.urls.size() > 1) {
				txtFile2.setText(this.urls.get(1));
			}
			if (this.urls.size() > 2) {
				txtFileN.setText(this.urls.get(this.urls.size() - 1));
			}
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	private List<String> generateUrls() {
		List<String> list = new ArrayList<>();
		String url = txtUrl.getText();
		if (url.indexOf('*') < 0) {
			return list;
		}
		if (radNum.isSelected()) {
			int v1 = (Integer) spFromModelNum.getValue();
			int v2 = (Integer) spToModelNum.getValue();
			int upper = Math.max(v1, v2);
			int lower = Math.min(v1, v2);
			for (int i = lower; i <= upper; i++) {
				String urlI = url.replace("*", i + "");
				list.add(urlI);
			}
			return list;
		} else {
			char v1 = ((String) spFromModelAlpha.getValue()).charAt(0);
			char v2 = ((String) spToModelAlpha.getValue()).charAt(0);
			int upper = Math.max(v1, v2);
			int lower = Math.min(v1, v2);
			for (int i = lower; i <= upper; i++) {
				String urlI = url.replace("*", (char) i + "");
				list.add(urlI);
			}
		}
		return list;
	}

	@Override
	public void changedUpdate(DocumentEvent e) {
		update(e);
	}

	@Override
	public void insertUpdate(DocumentEvent e) {
		update(e);
	}

	@Override
	public void removeUpdate(DocumentEvent e) {
		update(e);
	}

	void update(DocumentEvent e) {
		makeUrls();
	}

	@Override
	public void stateChanged(ChangeEvent e) {
		makeUrls();
	}

}
