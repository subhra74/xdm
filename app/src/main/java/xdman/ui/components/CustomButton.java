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

import javax.swing.Icon;
import javax.swing.JButton;

import xdman.ui.res.ColorResource;

@SuppressWarnings("unused")
public class CustomButton extends JButton {

	private static final long serialVersionUID = 6378409011977437191L;
	private Color rolloverBackground, rolloverForeground;
	private Color pressedBackground, pressedForeground;

	public CustomButton() {
		init();
	}

	public CustomButton(Icon icon) {
		super(icon);
		init();
	}

	public CustomButton(String text) {
		super(text);
		init();
	}

	private void init() {
		rolloverBackground = ColorResource.getSelectionColor();
		rolloverForeground = Color.WHITE;
		pressedBackground = ColorResource.getDarkBgColor();
		pressedForeground = Color.WHITE;
	}

	@Override
	public Color getForeground() {
		if (model.isRollover()) {
			return rolloverForeground;
		} else if (model.isPressed()) {
			return pressedForeground;
		} else {
			return super.getForeground();
		}
	}

	@Override
	public Color getBackground() {
		if (model.isPressed()) {
			return pressedBackground;
		} else if (model.isRollover()) {
			return rolloverBackground;
		} else {
			return super.getBackground();
		}
	}

	public static long getSerialVersionUID() {
		return serialVersionUID;
	}

	public final Color getRolloverBackground() {
		return rolloverBackground;
	}

	public final void setRolloverBackground(Color rolloverBackground) {
		this.rolloverBackground = rolloverBackground;
	}

	public final Color getRolloverForeground() {
		return rolloverForeground;
	}

	public final void setRolloverForeground(Color rolloverForeground) {
		this.rolloverForeground = rolloverForeground;
	}

	public final Color getPressedBackground() {
		return pressedBackground;
	}

	public final void setPressedBackground(Color pressedBackground) {
		this.pressedBackground = pressedBackground;
	}

	public final Color getPressedForeground() {
		return pressedForeground;
	}

	public final void setPressedForeground(Color pressedForeground) {
		this.pressedForeground = pressedForeground;
	}

}
