package xdman.ui.res;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.MultiResolutionImage;
import java.io.IOException;
import java.net.URL;
import java.util.*;

import javax.imageio.ImageIO;
import javax.swing.*;

import xdman.XDMConstants;
import xdman.util.XDMUtils;

public class ImageResource {
//	private final static String ICON_FOLDER = "icons";
//
//	static Map<String, ImageIcon> iconMap = new HashMap<String, ImageIcon>();
//
//	public static ImageIcon get(String id) {
//		return get(id, true);
//	}
//
//	public static ImageIcon get(String id, boolean cacheResult) {
//		ImageIcon icon = iconMap.get(id);
//		if (icon == null) {
//			icon = getIcon(id);
//			if (icon != null && cacheResult) {
//				iconMap.put(id, icon);
//			}
//		}
//		return icon;
//	}

	public static Image getImage(String name) {
		try {
			URL url=ImageResource.class.getResource("/icons/xxhdpi/" + name);
			System.out.println("Loading image from url: "+url);
			return ImageIO.read(url);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	public static Icon getIcon(String icon, int width, int height) {
		try {
			BufferedImage image = ImageIO.read(ImageResource.class.getResource("/icons/xxhdpi/" + icon));
			BufferedImage scaledImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

			//System.out.println("------*** " + image.getWidth() + " " + width);

			Graphics2D g2 = scaledImage.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2.drawImage(image, 0, 0, width, height, new ImageObserver() {
				@Override
				public boolean imageUpdate(Image img, int infoflags, int x, int y, int width, int height) {
					return true;
				}
			});
			g2.dispose();
			image.flush();
			return new Icon() {

				@Override
				public void paintIcon(Component c, Graphics g, int x, int y) {
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
					g2.drawImage(scaledImage, x, y, c);
					g2.dispose();
				}

				@Override
				public int getIconWidth() {
					return width;
				}

				@Override
				public int getIconHeight() {
					return height;
				}
			};
		} catch (Exception e) {
			e.printStackTrace();
			return new ImageIcon();
		}
	}

//	private static ImageIcon getIcon(String name) {
//		try {
//
//			java.net.URL urlHdpi = ImageResource.class.getResource("/" + ICON_FOLDER + "/hdpi/" + name);
//			java.net.URL urlXhdpi = ImageResource.class.getResource("/" + ICON_FOLDER + "/xhdpi/" + name);
//			java.net.URL urlXxhdpi = ImageResource.class.getResource("/" + ICON_FOLDER + "/xxhdpi/" + name);
//
//			BaseMultiResolutionImage img = new BaseMultiResolutionImage(0, ImageIO.read(urlHdpi),
//					ImageIO.read(urlXhdpi), ImageIO.read(urlXxhdpi));
//			return new ImageIcon(img);
//		} catch (Exception e) {
//			return new ImageIcon();
//		}
//
////		int screenType = XDMUtils.detectScreenType();
////		String folder = "hdpi";
////		if (screenType == XDMConstants.XHDPI) {
////			folder = "xxhdpi";
////		} else if (screenType == XDMConstants.HDPI) {
////			folder = "xhdpi";
////		} else {
////			folder = "hdpi";
////		}
////		System.out.println("icon type:"+folder);
////		try {
////			java.net.URL url = ImageResource.class.getResource("/" + ICON_FOLDER + "/" + folder + "/" + name);
////			if (url == null)
////				throw new Exception();
////			return new ImageIcon(url);
////		} catch (Exception e) {
////			return new ImageIcon(ICON_FOLDER + "/" + folder + "/" + name);
////		}
//	}
}