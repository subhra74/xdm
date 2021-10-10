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

import java.awt.Color;
import java.awt.Graphics;

import javax.swing.JPanel;
import javax.swing.event.MouseInputAdapter;

import xdman.Config;

public class LayeredPanel extends JPanel {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6762824626211830873L;
	private final Color bgColor;

	public LayeredPanel(int opacity) {
		if (Config.getInstance().isNoTransparency()) {
			opacity = 255;
		}
		bgColor = new Color(0, 0, 0, opacity);
		setOpaque(false);
		setLayout(null);

		MouseInputAdapter ma = new MouseInputAdapter() {
		};

		addMouseListener(ma);
		addMouseMotionListener(ma);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(bgColor);
		g.fillRect(0, 0, getWidth(), getHeight());
	}

}
