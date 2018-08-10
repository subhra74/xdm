package xdman.ui.components;

import java.awt.Graphics;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

import xdman.util.StringUtils;

import static xdman.util.XDMUtils.getScaledInt;

public class FormatImageLabel extends JLabel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -7575672895109288082L;
	String format;
	int scaleFactor;
	ImageIcon icon;

	public FormatImageLabel(int scaleFactor, ImageIcon icon) {
		this.scaleFactor = scaleFactor;
		this.icon = icon;
	}

	public void setFormat(String ext) {
		this.format = ext;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		int imageX = getWidth() / 2 - icon.getIconWidth() / 2;
		int imageY = getHeight() / 2 - icon.getIconHeight() / 2;
		icon.paintIcon(this, g, imageX, imageY);
		g.setFont(getFont());
		if (!StringUtils.isNullOrEmptyOrBlank(format)) {
			int stringWidth = g.getFontMetrics().stringWidth(format);
			g.drawString(format, getWidth() / 2 - stringWidth / 2, imageY + getScaledInt(30));
		}
	}
}
