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

import javax.swing.JComponent;
import javax.swing.JSlider;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSliderUI;

import xdman.ui.res.ColorResource;

public class XDMSliderUI extends BasicSliderUI {

	public XDMSliderUI(JSlider b) {
		super(b);
	}

	public static ComponentUI createUI(JComponent c) {
		return new XDMSliderUI((JSlider) c);
	}

	@Override
	public void paintTrack(Graphics g) {
		g.setColor(ColorResource.getDarkBgColor());
		g.fillRect(trackRect.x, trackRect.height / 2 - getScaledInt(2), super.trackRect.width, getScaledInt(4));
		g.setColor(ColorResource.getSelectionColor());
		g.fillRect(trackRect.x, trackRect.height / 2 - getScaledInt(2), super.thumbRect.x, getScaledInt(4));
	}

	@Override
	public void paintFocus(Graphics g) {
	}

	@Override
	public void paintThumb(Graphics g) {
		g.setColor(Color.WHITE);
		g.fillRect(thumbRect.x, trackRect.height / 2 - getScaledInt(2), super.thumbRect.width, getScaledInt(4));
	}

}
