package xdman.ui.components;

import java.awt.Graphics;
import java.awt.Image;

import javax.swing.JPanel;

import xdman.ui.res.ImageResource;

public class SidePanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 3821650643051584496L;
	Image imgBar;

	public SidePanel() {
		super();
		imgBar = ImageResource.get("bg_nav.png").getImage();
		this.setOpaque(false);
	}

	@Override
	protected void paintComponent(Graphics g) {
		g.drawImage(imgBar, 0, 0, this.getWidth(), this.getHeight(), this);// ,
		super.paintComponent(g);
	}
}
