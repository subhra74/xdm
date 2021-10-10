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

package xdman.ui.res;

import java.awt.Color;

public class ColorResource {
	private static Color whiteColor = new Color(235, 235, 235);
	private static Color titleColor = new Color(14, 20, 25);
	private static Color selectedColor = new Color(51, 181, 229);
	private static Color activeTabColor = new Color(242, 242, 242);
	private static Color darkBgColor = new Color(73, 73, 73);
	private static Color darkerBgColor = new Color(50, 50, 50);
	private static Color darkPressedColor = new Color(43, 43, 43);
	private static Color deepFontColor = new Color(160, 160, 160);
	private static Color lightFontColor = new Color(190, 190, 190);
	private static Color darkBtnColor = new Color(73, 73, 73);
	private static Color darkestBgColor = new Color(30, 30, 30);

	public static final Color getActiveTabColor() {
		return activeTabColor;
	}

	public static final Color getWhite() {
		return whiteColor;
	}

	public static final Color getTitleColor() {
		return titleColor;
	}

	public static final Color getSelectionColor() {
		return selectedColor;
	}

	public static final Color getButtonBackColor() {
		return selectedColor;
	}

	public static final Color getDarkBgColor() {
		return darkBgColor;
	}

	public static final Color getDarkerBgColor() {
		return darkerBgColor;
	}

	public static Color getDarkPressedColor() {
		return darkPressedColor;
	}

	public static final Color getDeepFontColor() {
		return deepFontColor;
	}

	public static final Color getLightFontColor() {
		return lightFontColor;
	}

	public static final Color getDarkBtnColor() {
		return darkBtnColor;
	}

	public static final void setDarkBtnColor(Color darkBtnColor) {
		ColorResource.darkBtnColor = darkBtnColor;
	}

	public static Color getDarkestBgColor() {
		return darkestBgColor;
	}

	public static void setDarkestBgColor(Color darkestBgColor) {
		ColorResource.darkestBgColor = darkestBgColor;
	}
}
