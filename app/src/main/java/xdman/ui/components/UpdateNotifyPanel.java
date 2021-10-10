/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

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

		JButton btn = new JButton(StringResource.get("LBL_INSTALL_NOW"));
		btn.setFont(FontResource.getBigBoldFont());
		btn.setName("OPT_UPDATE_FFMPEG");
		btn.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (mode == UpdateChecker.APP_UPDATE_AVAILABLE) {
					XDMUtils.browseURL(XDMApp.APP_UPDATE_CHK_URL + XDMApp.APP_VERSION);
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
			setBackground(new Color(216, 1, 0));
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
