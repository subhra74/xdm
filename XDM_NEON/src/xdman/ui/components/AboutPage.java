package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import xdman.XDMApp;
import xdman.ui.res.FontResource;
import xdman.ui.res.StringResource;
public class AboutPage extends Page {
	/**
	 * 
	 */
	private static final long serialVersionUID = -1284170515876454911L;

	public AboutPage(XDMFrame xframe) {
		super(StringResource.get("TITLE_ABOUT"), getScaledInt(350), xframe);
		int y = 0;
		int h = 0;
		JPanel panel = new JPanel();
		panel.setLayout(null);
		panel.setOpaque(false);
		y += getScaledInt(10);
		h = getScaledInt(50);

		JLabel lblTitle = new JLabel(StringResource.get("FULL_NAME"));
		lblTitle.setFont(FontResource.getBiggerFont());
		lblTitle.setForeground(Color.WHITE);
		lblTitle.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblTitle);

		y += h;
		y += getScaledInt(20);

		String details = String.format(StringResource.get("ABOUT_DETAILS"), XDMApp.APP_VERSION,
				System.getProperty("java.version"), System.getProperty("os.name"), "http://xdman.sourceforge.net");

		h = getScaledInt(250);
		JTextArea lblDetails = new JTextArea();
		lblDetails.setOpaque(false);
		lblDetails.setWrapStyleWord(true);
		lblDetails.setLineWrap(true);
		lblDetails.setEditable(false);
		lblDetails.setForeground(Color.WHITE);
		lblDetails.setText(details);
		lblDetails.setFont(FontResource.getBigFont());
		lblDetails.setBounds(getScaledInt(15), y, getScaledInt(350) - getScaledInt(30), h);
		panel.add(lblDetails);
		y += h;

		panel.setPreferredSize(new Dimension(getScaledInt(350), y));
		panel.setBounds(0, 0, getScaledInt(350), y);

		jsp.setViewportView(panel);
	}
}
