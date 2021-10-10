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

package xdman.ui.laf;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;

@SuppressWarnings("unused")
public class XDMToolBarButtonUI extends BasicButtonUI {
	Color pressedColor = new Color(170, 170, 170), rolloverColor = new Color(180, 180, 180);

	protected void paintButtonNormal(Graphics g, AbstractButton b) {
	}

	protected void paintButtonPressed(Graphics g, AbstractButton b) {

		Graphics2D g2 = (Graphics2D) g;

		g2.setColor(pressedColor);
		g2.fillRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
	}

	protected void paintButtonRollOver(Graphics g, AbstractButton b) {

		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(rolloverColor);
		g2.fillRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
	}

	public void paint(Graphics g, JComponent c) {
		AbstractButton b = (AbstractButton) c;
		ButtonModel bm = b.getModel();
		if (bm.isRollover()) {
			paintButtonRollOver(g, b);
		} else {
			paintButtonNormal(g, b);
		}
		super.paint(g, c);
	}

}
