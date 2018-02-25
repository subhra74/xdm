package xdman.ui.res;

import java.util.*;

import javax.swing.*;

import xdman.XDMConstants;
import xdman.util.XDMUtils;

public class ImageResource {
	private final static String ICON_FOLDER = "icons";

	static Map<String, ImageIcon> iconMap = new HashMap<String, ImageIcon>();

	public static ImageIcon get(String id) {
		return get(id, true);
	}

	public static ImageIcon get(String id, boolean cacheResult) {
		ImageIcon icon = iconMap.get(id);
		if (icon == null) {
			icon = getIcon(id);
			if (icon != null && cacheResult) {
				iconMap.put(id, icon);
			}
		}
		return icon;
	}

	private static ImageIcon getIcon(String name) {
		int screenType = XDMUtils.detectScreenType();
		String folder = "hdpi";
		if (screenType == XDMConstants.XHDPI) {
			folder = "xxhdpi";
		} else if (screenType == XDMConstants.HDPI) {
			folder = "xhdpi";
		} else {
			folder = "hdpi";
		}
		System.out.println("icon type:"+folder);
		try {
			java.net.URL url = ImageResource.class.getResource("/" + ICON_FOLDER + "/" + folder + "/" + name);
			if (url == null)
				throw new Exception();
			return new ImageIcon(url);
		} catch (Exception e) {
			return new ImageIcon(ICON_FOLDER + "/" + folder + "/" + name);
		}
	}
}