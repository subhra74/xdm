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
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicMenuItemUI;

import xdman.ui.res.ColorResource;

@SuppressWarnings("unused")
public class XDMMenuItemUI extends BasicMenuItemUI {
	Color colorSelect, colorBg;

	public static ComponentUI createUI(JComponent c) {
		return new XDMMenuItemUI();
	}

	public XDMMenuItemUI() {
		colorSelect = ColorResource.getSelectionColor();
		colorBg = ColorResource.getDarkerBgColor();// Color.WHITE;
	}

	@Override
	protected Dimension getPreferredMenuItemSize(JComponent c, Icon checkIcon, Icon arrowIcon, int defaultTextIconGap) {
		Dimension d = super.getPreferredMenuItemSize(c, checkIcon, arrowIcon, defaultTextIconGap);
		return new Dimension(d.width + getScaledInt(10), d.height);
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		c.setBorder(null);
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
		int menuWidth = menuItem.getWidth();
		int menuHeight = menuItem.getHeight();

		Color bgc = (Color) menuItem.getClientProperty("bgColor");
		if (bgc != null) {
			g.setColor(bgc);
		} else {
			g.setColor(colorBg);
		}
		g.fillRect(0, 0, menuWidth, menuHeight);

		if (model.isArmed() || (menuItem instanceof JMenu && model.isSelected())) {
			paintButtonPressed(g, menuItem);
		}

		if (menuItem instanceof JCheckBoxMenuItem) {
			menuItem.isSelected();
		}

		g.setColor(oldColor);
	}

}
