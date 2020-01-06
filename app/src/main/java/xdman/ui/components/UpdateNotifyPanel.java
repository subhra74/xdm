package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import xdman.XDMApp;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.StringResource;
import xdman.util.FFmpegDownloader;
import xdman.util.UpdateChecker;
import xdman.util.XDMUtils;
import static xdman.util.XDMUtils.getScaledInt;
public class UpdateNotifyPanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 736299130280521327L;
	int mode;
	JLabel lbl, desc;

	public UpdateNotifyPanel() {
		super(new BorderLayout());
		setBorder(new EmptyBorder(getScaledInt(10), getScaledInt(15), getScaledInt(10), getScaledInt(15)));
		JPanel p2 = new JPanel(new BorderLayout());
		p2.setOpaque(false);
		lbl = new JLabel();
		lbl.setFont(FontResource.getItemFont());
		p2.add(lbl);
		desc = new JLabel();
		p2.add(desc, BorderLayout.SOUTH);
		add(p2, BorderLayout.CENTER);

		// b.add(Box.createHorizontalGlue());
		JButton btn = new JButton(StringResource.get("LBL_INSTALL_NOW"));
		btn.setFont(FontResource.getBigBoldFont());
		btn.setName("OPT_UPDATE_FFMPEG");
		btn.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (mode == UpdateChecker.APP_UPDATE_AVAILABLE) {
					XDMUtils.browseURL("http://xdman.sourceforge.net/update/update.php?ver=" + XDMApp.APP_VERSION);
				} else {
					FFmpegDownloader fd = new FFmpegDownloader();
					fd.start();
				}
				XDMApp.getInstance().clearNotifications();
			}
		});
		add(btn, BorderLayout.EAST);
	}

	public void setDetails(int mode) {
		if (mode == UpdateChecker.COMP_NOT_INSTALLED) {
			setBackground(new Color(216,1,0));
		} else {
			setBackground(ColorResource.getDarkestBgColor());
		}
		if (mode == UpdateChecker.APP_UPDATE_AVAILABLE) {
			lbl.setText(StringResource.get("LBL_APP_OUTDATED"));
			desc.setText(StringResource.get("LBL_UPDATE_DESC"));
		}
		if (mode == UpdateChecker.COMP_NOT_INSTALLED) {
			lbl.setText(StringResource.get("LBL_COMPONENT_MISSING"));
			desc.setText(StringResource.get("LBL_COMPONENT_DESC"));
		}
		if (mode == UpdateChecker.COMP_UPDATE_AVAILABLE) {
			lbl.setText(StringResource.get("LBL_COMPONENT_OUTDATED"));
			desc.setText(StringResource.get("LBL_COMPONENT_DESC"));
		}

		if (mode == UpdateChecker.COMP_NOT_INSTALLED) {
			lbl.setText(StringResource.get("LBL_COMPONENT_MISSING"));
		}
		this.mode = mode;
	}
}
