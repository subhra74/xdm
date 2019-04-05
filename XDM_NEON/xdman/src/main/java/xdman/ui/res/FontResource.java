package xdman.ui.res;

import java.awt.Font;

import xdman.util.Logger;
import xdman.util.XDMUtils;

public class FontResource {
	static {
		Logger.log("Loading fonts");
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