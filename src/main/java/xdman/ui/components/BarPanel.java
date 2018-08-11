package xdman.ui.components;

import xdman.ui.res.ImageResource;

import javax.swing.*;
import java.awt.*;

public class BarPanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -5396480713429517585L;
	Image imgBar;

	public BarPanel() {
		super();
		imgBar = ImageResource.get("bar.png").getImage();
		this.setOpaque(false);
	}

	@Override
	protected void paintComponent(Graphics g) {
		g.drawImage(imgBar, 0, 0, this.getWidth(), this.getHeight(), this);// ,
		super.paintComponent(g);
	}
}
