package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.LineBorder;

import xdman.LinkRefreshCallback;
import xdman.XDMApp;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;
public class RefreshUrlPage extends Page implements LinkRefreshCallback {
	/**
	 * 
	 */
	private static final long serialVersionUID = 3725469968275980073L;
	private static RefreshUrlPage page;
	private HttpMetadata md;
	private JButton btnOpenPage;
	private JTextArea lblMonitoringTitle;
	private JTextField txtUrl;
	private JLabel lblUrl;
	private JButton btnSave;

	private RefreshUrlPage(XDMFrame xframe) {
		super(StringResource.get("REF_TITLE"), getScaledInt(350), xframe);
		initUI();
	}

	public void setDetails(HttpMetadata md) {
		this.md = md;
		if (StringUtils.isNullOrEmptyOrBlank(md.getYdlUrl())) {
			btnOpenPage.setVisible(md.getHeaders().containsHeader("referer"));
		} else {
			btnOpenPage.setVisible(true);
		}
		System.out.println("ydlurl: " + md.getYdlUrl());
		lblMonitoringTitle.setText(StringUtils.isNullOrEmptyOrBlank(md.getYdlUrl()) ? StringResource.get("REF_DESC1")
				: StringResource.get("REF_DESC2"));

	}

	private void initUI() {
		int y = 0;
		int h = 0;
		JPanel panel = new JPanel();
		panel.setLayout(null);
		panel.setOpaque(false);
		y += getScaledInt(10);

		h = getScaledInt(40);
		JLabel lblMaxTitle = new JLabel(StringResource.get("REF_WAITING_FOR_LINK"));
		lblMaxTitle.setForeground(Color.WHITE);
		lblMaxTitle.setFont(FontResource.getItemFont());
		lblMaxTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblMaxTitle);
		y += h;
		y += getScaledInt(10);

		h = getScaledInt(80);

		lblMonitoringTitle = new JTextArea();
		lblMonitoringTitle.setOpaque(false);
		lblMonitoringTitle.setWrapStyleWord(true);
		lblMonitoringTitle.setLineWrap(true);
		lblMonitoringTitle.setEditable(false);
		lblMonitoringTitle.setForeground(Color.WHITE);
		lblMonitoringTitle.setFont(FontResource.getNormalFont());
		lblMonitoringTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblMonitoringTitle);
		y += h;

		btnOpenPage = createButton1("REF_OPEN_PAGE", getScaledInt(15), y);
		btnOpenPage.setName("REF_OPEN_PAGE");
		panel.add(btnOpenPage);
		y += btnOpenPage.getHeight();
		btnOpenPage.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if ((!StringUtils.isNullOrEmptyOrBlank(md.getYdlUrl())) || md.getHeaders().containsHeader("referer")) {
					openLink();
				}
			}
		});

		y += getScaledInt(30);

		final JCheckBox chk = new JCheckBox(StringResource.get("SETTINGS_ADV"));
		chk.setName("SETTINGS_ADV");
		chk.setIcon(ImageResource.get("unchecked.png"));
		chk.setSelectedIcon(ImageResource.get("checked.png"));
		chk.setOpaque(false);
		chk.setFocusPainted(false);
		chk.setForeground(Color.WHITE);
		chk.setFont(FontResource.getNormalFont());
		chk.setBounds(getScaledInt(15), y, getScaledInt(350) - 2 * getScaledInt(15), getScaledInt(30));
		panel.add(chk);
		chk.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				showUrlTextBox(chk.isSelected());
			}
		});

		y += getScaledInt(30);
		h = getScaledInt(30);

		lblUrl = new JLabel(StringResource.get("ND_ADDRESS"));
		lblUrl.setBounds(getScaledInt(15), y, getScaledInt(80), getScaledInt(30));
		lblUrl.setFont(FontResource.getNormalFont());
		lblUrl.setVisible(false);
		panel.add(lblUrl);

		txtUrl = new JTextField();
		txtUrl.setBorder(new LineBorder(ColorResource.getDarkBtnColor()));
		txtUrl.setForeground(Color.WHITE);
		txtUrl.setOpaque(false);
		txtUrl.setBounds(getScaledInt(95), y + getScaledInt(5), getScaledInt(350) - getScaledInt(95) - getScaledInt(15), getScaledInt(20));
		txtUrl.setVisible(false);
		panel.add(txtUrl);

		y += h;

		btnSave = createButton1("DESC_SAVE_Q", getScaledInt(15), y);
		btnSave.setName("DESC_SAVE_Q");
		btnSave.setVisible(false);
		panel.add(btnSave);
		y += btnSave.getHeight();
		btnSave.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (!StringUtils.isNullOrEmptyOrBlank(txtUrl.getText())) {
					try {
						new URL(txtUrl.getText());
						if (md != null) {
							md.setUrl(txtUrl.getText());
							md.save();
						}
					} catch (Exception ex) {
						MessageBox.show(getParentFrame(), StringResource.get("MSG_REF_LINK_CONFIRM"),
								StringResource.get("MSG_INVALID_URL"), MessageBox.OK, MessageBox.OK);
						Logger.log(ex);
					}
				}else {
					MessageBox.show(getParentFrame(), StringResource.get("REF_TITLE"),
							StringResource.get("MSG_NO_URL"), MessageBox.OK, MessageBox.OK);
				}
			}
		});

		y += getScaledInt(30);

		panel.setPreferredSize(new Dimension(getScaledInt(350), y));
		panel.setBounds(0, 0, getScaledInt(350), y);

		jsp.setViewportView(panel);
	}

	private void showUrlTextBox(boolean show) {
		lblUrl.setVisible(show);
		txtUrl.setVisible(show);
		btnSave.setVisible(show);
	}

	public static RefreshUrlPage getPage(XDMFrame xframe) {
		if (page == null) {
			page = new RefreshUrlPage(xframe);
		}
		return page;
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
		// btn.addActionListener(this);
		return btn;
	}

	@Override
	public String getId() {
		return md.getId();
	}

	@Override
	public boolean isValidLink(HttpMetadata metadata) {
		Logger.log("Checking refresh link with checking size " + md.getSize());
		if (md.getType() == metadata.getType()) {
			if (md instanceof DashMetadata) {
				DashMetadata dm1 = (DashMetadata) md;
				DashMetadata dm2 = (DashMetadata) metadata;
				if (dm1.getLen1() == dm2.getLen1() && dm1.getLen2() == dm2.getLen2()) {
					dm1.setUrl(dm2.getUrl());
					dm1.setUrl2(dm2.getUrl2());
					dm1.setHeaders(dm2.getHeaders());
					dm1.setLen1(dm2.getLen1());
					dm1.setLen2(dm2.getLen2());
					dm1.save();
					showOkMsgAndClose();
					return true;
				}
			} else if (md instanceof HlsMetadata) {
				HlsMetadata hm1 = (HlsMetadata) md;
				HlsMetadata hm2 = (HlsMetadata) metadata;
				if (confirmUrl("")) {
					hm1.setUrl(hm2.getUrl());
					hm1.setHeaders(hm2.getHeaders());
					hm1.save();
					showOkMsgAndClose();
					return true;
				}
			} else if (md instanceof HdsMetadata) {
				HdsMetadata hm1 = (HdsMetadata) md;
				HdsMetadata hm2 = (HdsMetadata) metadata;
				if (confirmUrl("")) {
					hm1.setUrl(hm2.getUrl());
					hm1.setHeaders(hm2.getHeaders());
					hm1.save();
					showOkMsgAndClose();
					return true;
				}
			} else {
				boolean confirmed = false;
				if (md.getSize() > 0) {
					confirmed = md.getSize() == metadata.getSize();
				} else {
					confirmed = confirmUrl(StringResource.get("MSG_REF_LINK_QUESTION"));
				}
				if (confirmed) {
					md.setUrl(metadata.getUrl());
					md.setHeaders(metadata.getHeaders());
					md.save();
					showOkMsgAndClose();
					return true;
				}
			}
		}
		return false;
	}

	private boolean confirmUrl(String msg) {
		return (MessageBox.show(super.getParentFrame(), StringResource.get("MSG_REF_LINK_CONFIRM"), msg,
				MessageBox.YES_NO_OPTION, MessageBox.YES) == MessageBox.YES);
	}

	private void showOkMsgAndClose() {
		MessageBox.show(getParentFrame(), StringResource.get("MSG_REF_LINK_CONFIRM"),
				StringResource.get("MSG_REF_LINK_MSG"), MessageBox.OK, MessageBox.OK);
	}

	@Override
	public void showPanel() {
		super.showPanel();
		XDMApp.getInstance().registerRefreshCallback(this);
	}

	@Override
	public void close() {
		XDMApp.getInstance().unregisterRefreshCallback();
		super.close();
	}

	private void openLink() {
		if (!StringUtils.isNullOrEmptyOrBlank(md.getYdlUrl())) {
			MediaDownloaderWnd wnd = new MediaDownloaderWnd();
			wnd.launchWithUrl(md.getYdlUrl());
		} else if (md.getHeaders().containsHeader("referer")) {
			XDMUtils.browseURL(md.getHeaders().getValue("referer"));
		}
	}
}
