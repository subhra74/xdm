package xdman.ui.res;

import java.awt.*;

public class ColorResource {
	private static Color whiteColor = new Color(235, 235, 235);
	private static Color titleColor = new Color(14, 20, 25);
	private static Color selectedColor =  new Color(51, 181, 229);
	private static Color activeTabColor = new Color(242, 242, 242);
	private static Color darkBgColor = new Color(73, 73, 73);
	private static Color darkerBgColor = new Color(50, 50, 50);
	private static Color darkPressedColor = new Color(43, 43, 43);
	private static Color deepFontColor = new Color(160, 160, 160);
	private static Color lightFontColor = new Color(190, 190, 190);
	private static Color darkBtnColor = new Color(73, 73, 73);// new Color(83,
																// 83, 83);
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
