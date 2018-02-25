package xdman.ui.components;

import javax.swing.*;
import javax.swing.event.*;

import xdman.Config;

import java.awt.*;

public class LayeredPanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 6762824626211830873L;
	private Color bgColor;

	public LayeredPanel(int opacity) {
		if (Config.getInstance().isNoTransparency()) {
			opacity = 255;
		}
		bgColor = new Color(0, 0, 0, opacity);
		setOpaque(false);
		setLayout(null);

		MouseInputAdapter ma = new MouseInputAdapter() {
		};

		addMouseListener(ma);
		addMouseMotionListener(ma);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(bgColor);
		g.fillRect(0, 0, getWidth(), getHeight());
	}
}
