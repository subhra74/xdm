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

import java.awt.Font;

import org.tinylog.Logger;

import xdman.util.XDMUtils;

public class FontResource {
	static {
		Logger.info("Loading fonts");
	}

	public static Font getNormalFont() {
		if (plainFont == null) {
			plainFont = new Font(Font.SANS_SERIF, Font.PLAIN, XDMUtils.getScaledInt(12));
		}
		return plainFont;
	}

	public static Font getBoldFont() {
		if (boldFont == null) {
			boldFont = new Font(Font.SANS_SERIF, Font.BOLD, XDMUtils.getScaledInt(12));
		}
		return boldFont;
	}

	public static Font getBigFont() {
		if (plainFontBig == null) {
			plainFontBig = new Font(Font.SANS_SERIF, Font.PLAIN, XDMUtils.getScaledInt(14));
		}
		return plainFontBig;
	}

	public static Font getBigBoldFont() {
		if (boldFont2 == null) {
			boldFont2 = new Font(Font.SANS_SERIF, Font.BOLD, XDMUtils.getScaledInt(14));
		}
		return boldFont2;
	}

	public static Font getItemFont() {
		if (itemFont == null) {
			itemFont = new Font(Font.SANS_SERIF, Font.PLAIN, XDMUtils.getScaledInt(16));
		}
		return itemFont;
	}

	public static Font getBiggerFont() {
		if (plainFontBig1 == null) {
			plainFontBig1 = new Font(Font.SANS_SERIF, Font.PLAIN, XDMUtils.getScaledInt(18));
		}
		return plainFontBig1;
	}

	public static Font getBiggestFont() {
		if (plainFontBig2 == null) {
			plainFontBig2 = new Font(Font.SANS_SERIF, Font.PLAIN, XDMUtils.getScaledInt(24));
		}
		return plainFontBig2;
	}

	private static Font plainFont;
	private static Font boldFont;
	private static Font boldFont2;

	private static Font plainFontBig;
	private static Font plainFontBig1;
	private static Font plainFontBig2;
	private static Font itemFont;
}