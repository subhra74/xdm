package xdman.ui.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import xdman.Config;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import static xdman.util.XDMUtils.getScaledInt;

public class BrowserAddonDlg extends JDialog implements ActionListener {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private String url, desc;

	public BrowserAddonDlg(String url, String desc) {
		setModal(true);
		this.url = url;
		this.desc = desc;
		initUI();
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JButton) {
			JButton btn = (JButton) e.getSource();
			String name = btn.getName();
			if ("CLOSE".equals(name)) {
				dispose();
			} else if ("COPY".equals(name)) {
				XDMUtils.copyURL(this.url);
			}
		}
	}

	private void initUI() {
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

		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(400), getScaledInt(300));
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(400), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(365), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("BROWSER_MONITORING"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(400), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);
		add(titlePanel);

		int y = getScaledInt(65);
		int h = getScaledInt(50);
		JTextArea lblMonitoringTitle = new JTextArea();
		lblMonitoringTitle.setOpaque(false);
		lblMonitoringTitle.setWrapStyleWord(true);
		lblMonitoringTitle.setLineWrap(true);
		lblMonitoringTitle.setEditable(false);
		lblMonitoringTitle.setForeground(Color.WHITE);
		lblMonitoringTitle.setText(this.desc);
		lblMonitoringTitle.setFont(FontResource.getNormalFont());
		lblMonitoringTitle.setBounds(getScaledInt(15), y, getScaledInt(370) - getScaledInt(30), h);
		add(lblMonitoringTitle);
		y += h;

		JButton btViewMonitoring = createButton1("CTX_COPY_URL", getScaledInt(15), y);
		btViewMonitoring.setName("COPY");
		add(btViewMonitoring);
		y += btViewMonitoring.getHeight();

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

}
