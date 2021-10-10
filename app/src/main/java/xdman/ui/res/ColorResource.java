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

@SuppressWarnings("unused")
public class ColorResource {
	private static final Color whiteColor = new Color(235, 235, 235);
	private static final Color titleColor = new Color(14, 20, 25);
	private static final Color selectedColor = new Color(51, 181, 229);
	private static final Color activeTabColor = new Color(242, 242, 242);
	private static final Color darkBgColor = new Color(73, 73, 73);
	private static final Color darkerBgColor = new Color(50, 50, 50);
	private static final Color darkPressedColor = new Color(43, 43, 43);
	private static final Color deepFontColor = new Color(160, 160, 160);
	private static final Color lightFontColor = new Color(190, 190, 190);
	private static Color darkBtnColor = new Color(73, 73, 73);
	private static Color darkestBgColor = new Color(30, 30, 30);

	public static Color getActiveTabColor() {
		return activeTabColor;
	}

	public static Color getWhite() {
		return whiteColor;
	}

	public static Color getTitleColor() {
		return titleColor;
	}

	public static Color getSelectionColor() {
		return selectedColor;
	}

	public static Color getButtonBackColor() {
		return selectedColor;
	}

	public static Color getDarkBgColor() {
		return darkBgColor;
	}

	public static Color getDarkerBgColor() {
		return darkerBgColor;
	}

	public static Color getDarkPressedColor() {
		return darkPressedColor;
	}

	public static Color getDeepFontColor() {
		return deepFontColor;
	}

	public static Color getLightFontColor() {
		return lightFontColor;
	}

	public static Color getDarkBtnColor() {
		return darkBtnColor;
	}

	public static void setDarkBtnColor(Color darkBtnColor) {
		ColorResource.darkBtnColor = darkBtnColor;
	}

	public static Color getDarkestBgColor() {
		return darkestBgColor;
	}

	public static void setDarkestBgColor(Color darkestBgColor) {
		ColorResource.darkestBgColor = darkestBgColor;
	}
}
