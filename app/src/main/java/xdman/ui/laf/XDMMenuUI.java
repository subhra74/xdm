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

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicMenuUI;

import xdman.ui.res.ColorResource;

@SuppressWarnings("unused")
public class XDMMenuUI extends BasicMenuUI {
	Color colorSelect, colorBg;

	public XDMMenuUI() {
		this.colorBg = ColorResource.getDarkerBgColor();
		this.colorSelect = ColorResource.getSelectionColor();
	}

	public static ComponentUI createUI(JComponent c) {
		return new XDMMenuUI();
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		if (c instanceof AbstractButton) {
			AbstractButton btn = (AbstractButton) c;
			btn.setBorder(new EmptyBorder(getScaledInt(5), getScaledInt(10), getScaledInt(5), getScaledInt(10)));
			btn.setBorderPainted(false);
		}
	}

	protected void paintButtonPressed(Graphics g, AbstractButton b) {
		Color c = g.getColor();

		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(colorSelect);
		g2.fillRect(0, 0, b.getWidth(), b.getHeight());

		g.setColor(c);
	}

	@Override
	protected void paintBackground(Graphics g, JMenuItem menuItem, Color bgColor) {
		ButtonModel model = menuItem.getModel();
		Color oldColor = g.getColor();
		if (model.isArmed() || (menuItem instanceof JMenu && model.isSelected())) {
			paintButtonPressed(g, menuItem);
		} else {
			g.setColor(this.colorBg);
		}
		g.setColor(oldColor);
	}

}
