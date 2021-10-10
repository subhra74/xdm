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

import java.awt.Graphics;

import javax.swing.Icon;
import javax.swing.JLabel;

import xdman.util.StringUtils;

public class FormatImageLabel extends JLabel {

	/**
	 * 
	 */
	private static final long serialVersionUID = -7575672895109288082L;
	String format;
	int scaleFactor;
	Icon icon;

	public FormatImageLabel(int scaleFactor, Icon icon) {
		this.scaleFactor = scaleFactor;
		this.icon = icon;
	}

	public void setFormat(String ext) {
		this.format = ext;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		int imageX = getWidth() / 2 - icon.getIconWidth() / 2;
		int imageY = getHeight() / 2 - icon.getIconHeight() / 2;
		icon.paintIcon(this, g, imageX, imageY);
		g.setFont(getFont());
		if (!StringUtils.isNullOrEmptyOrBlank(format)) {
			int stringWidth = g.getFontMetrics().stringWidth(format);
			g.drawString(format, getWidth() / 2 - stringWidth / 2, imageY + getScaledInt(30));
		}
	}

}
