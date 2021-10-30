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

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;

import org.tinylog.Logger;

public class ImageResource {

	public static Image getImage(String name) {
		try {
			URL url = ImageResource.class.getResource("/icons/xxhdpi/" + name);
			if (url != null ){
				Logger.info("Loading image from url: " + url);
				return ImageIO.read(url);
			}
		} catch (IOException e) {
			Logger.error(e);
		}
		return null;
	}

	public static Icon getIcon(String icon, int width, int height) {
		try {
			BufferedImage image = ImageIO.read(Objects.requireNonNull(ImageResource.class.getResource("/icons/xxhdpi/" + icon)));
			BufferedImage scaledImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

			Graphics2D g2 = scaledImage.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g2.drawImage(image, 0, 0, width, height, (img, infoflags, x, y, width1, height1) -> true);
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
			Logger.error(e);
			return new ImageIcon();
		}
	}

}