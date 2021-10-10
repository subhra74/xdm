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
import java.awt.Insets;

import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicProgressBarUI;

import xdman.ui.res.ColorResource;

public class XDMProgressBarUI extends BasicProgressBarUI {

	public static ComponentUI createUI(JComponent c) {
		return new XDMProgressBarUI();
	}

	@Override
	public void paint(Graphics g, JComponent c) {

		if (!(g instanceof Graphics2D)) {
			return;
		}

		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(Color.GRAY);
		g2.fillRect(0, 0, c.getWidth(), c.getHeight());
		if (progressBar.isIndeterminate()) {
			paintIndeterminate(g, c);
		} else {
			paintDeterminate(g, c);
		}

	}

	@Override
	protected void paintIndeterminate(Graphics g, JComponent c) {
		Insets b = progressBar.getInsets(); // area for border
		int barRectWidth = progressBar.getWidth() - (b.right + b.left);
		int barRectHeight = progressBar.getHeight() - (b.top + b.bottom);

		if (barRectWidth <= 0 || barRectHeight <= 0) {
			return;
		}

		Graphics2D g2 = (Graphics2D) g;

		boxRect = getBox(boxRect);
		if (boxRect != null) {
			g2.setPaint(ColorResource.getSelectionColor());
			g2.fillRect(boxRect.x, boxRect.y, boxRect.width, boxRect.height);
		}
	}

	@Override
	protected void paintDeterminate(Graphics g, JComponent c) {
		Insets b = progressBar.getInsets(); // area for border
		int barRectWidth = progressBar.getWidth() - (b.right + b.left);
		int barRectHeight = progressBar.getHeight() - (b.top + b.bottom);

		if (barRectWidth <= 0 || barRectHeight <= 0) {
			return;
		}

		int amountFull = getAmountFull(b, barRectWidth, barRectHeight);

		Graphics2D g2 = (Graphics2D) g;
		g2.setColor(ColorResource.getSelectionColor());

		if (progressBar.getOrientation() == JProgressBar.HORIZONTAL) {
			g2.fillRect(0, 0, amountFull, c.getHeight());
		}
	}

}
