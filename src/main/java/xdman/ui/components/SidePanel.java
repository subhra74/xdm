package xdman.ui.components;

import xdman.ui.res.ImageResource;

import javax.swing.*;
import java.awt.*;

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
