package xdman.ui.res;

import xdman.XDMConstants;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

public class ImageResource {
	private final static String ICON_FOLDER = "icons";

	static Map<String, ImageIcon> iconMap = new HashMap<>();

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
		String folder;
		if (screenType == XDMConstants.XHDPI) {
			folder = "xxhdpi";
		} else if (screenType == XDMConstants.HDPI) {
			folder = "xhdpi";
		} else {
			folder = "hdpi";
		}
		String iconFileName = String.format("%s/%s/%s", ICON_FOLDER, folder, name);
		Logger.log("icon:", iconFileName);
		try {
			java.net.URL url = ImageResource.class.getResource(String.format("/%s", iconFileName));
			if (url == null)
				throw new Exception();
			return new ImageIcon(url);
		} catch (Exception e) {
			return new ImageIcon(iconFileName);
		}
	}
}