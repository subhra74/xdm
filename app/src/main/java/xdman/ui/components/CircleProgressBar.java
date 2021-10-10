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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.font.LineMetrics;

import javax.swing.JComponent;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;

public class CircleProgressBar extends JComponent {

	private static final long serialVersionUID = 3778513245025142955L;
	private final int padding = getScaledInt(4);

	public CircleProgressBar() {
		foreColor = ColorResource.getSelectionColor();
		backColor = ColorResource.getDarkBgColor();
	}

	@Override
	public void paint(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;
		if (g2 == null) {
			return;
		}
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int sweep_angle = (int) (((float) value * 360) / 100);
		g2.setColor(Color.GRAY);
		g2.setStroke(stroke);
		g2.drawArc(padding, padding, getWidth() - 2 * padding, getHeight() - 2 * padding, getScaledInt(90), -360);
		if (value > 0) {
			g2.setColor(foreColor);
			g2.drawArc(padding, padding, getWidth() - 2 * padding, getHeight() - 2 * padding, getScaledInt(90),
					-sweep_angle);
		}

		g2.setFont(FontResource.getItemFont());
		FontMetrics fm = g2.getFontMetrics();
		String str = value + "%";
		int w = (int) fm.getStringBounds(str, g2).getWidth();// fm.stringWidth(str);
		LineMetrics lm = fm.getLineMetrics(str, g2);
		int h = (int) (lm.getAscent() + lm.getDescent());
		g2.drawString(str, (getWidth() - w) / 2, ((getHeight() + h) / 2) - lm.getDescent());
	}

	Stroke stroke = new BasicStroke(getScaledInt(4));
	private int value;

	Color foreColor, backColor;

	public void setValue(int value) {
		this.value = value;
		repaint();
	}

	public int getValue() {
		return value;
	}

}
