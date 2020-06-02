package xdman.ui.components;

import java.awt.*;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.*;

import xdman.ui.res.ImageResource;

public class BarPanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -5396480713429517585L;
	Image imgBar;

	public BarPanel() {
		super();
		try {
			imgBar = ImageIO.read(ImageResource.class.getResource("/icons/xxhdpi/bar.png"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.setOpaque(false);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2=(Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g2.drawImage(imgBar, 0, 0, this.getWidth(), this.getHeight(), this);// ,
		super.paintComponent(g2);
	}
}
