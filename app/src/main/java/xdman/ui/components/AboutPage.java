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
		int h;
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

		String details = String.format(
				"Version %s with Java %s on %s\n\nCreated by: Subhra Das Gupta\n\n%s\nCopyright (C) 2020, All rights reserved.",
				XDMApp.APP_VERSION, (System.getProperty("java.vendor") + " " + System.getProperty("java.version")),
				System.getProperty("os.name"), "https://github.com/subhra74/xdm");

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
