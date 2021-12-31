package xdman.ui.laf;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.SwingConstants;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

import xdman.ui.components.CustomButton;
import xdman.ui.components.DarkScrollBar;
import xdman.ui.res.ImageResource;
import xdman.util.XDMUtils;

public class XDMScrollBarUI extends BasicScrollBarUI {

	public static ComponentUI createUI(JComponent c) {
		return new XDMScrollBarUI();
	}

	// Color borderColor = new Color(170, 170, 170);

	public XDMScrollBarUI() {
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);

		c.setPreferredSize(new Dimension(XDMUtils.getScaledInt(8), XDMUtils.getScaledInt(8)));
	}

	protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
		if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
			return;
		}

		int w = thumbBounds.width;
		int h = thumbBounds.height;

		g.translate(thumbBounds.x, thumbBounds.y);

		Graphics2D g2 = (Graphics2D) g;
		g2.setColor(c.getForeground());

//		if (isThumbRollover()) {
//			g2.setColor(darkMode ? roColor2 : roColor1);
//		} else {
//			g2.setColor(darkMode ? barColor2 : barColor1);
//		}

		// g.fillRect(1, 0, w - 3, h - 1);
		g.fillRect(0, 0, w, h);

		// g2.setColor(borderColor);
		// g.drawRect(1, 0, w - 3, h - 1);
		g.translate(-thumbBounds.x, -thumbBounds.y);
	}

	@Override
	protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
		g.setColor(c.getBackground());
		// g.setColor(darkMode ? trackColor2 : trackColor1);
		g.translate(r.x, r.y);
		g.fillRect(0, 0, r.width, r.height);
		g.translate(-r.x, -r.y);
		// super.paintTrack(g, c, r);
	}

	protected JButton createDecreaseButton(int orientation) {
		return createScrollButton(orientation);
	}

	protected JButton createIncreaseButton(int orientation) {
		return createScrollButton(orientation);
	}

	protected JButton createZeroButton() {
		JButton button = new JButton();
		Dimension zeroDim = new Dimension(0, 0);
		button.setPreferredSize(zeroDim);
		button.setMinimumSize(zeroDim);
		button.setMaximumSize(zeroDim);
		return button;
	}

	private JButton createScrollButton(int orientation) {
		return createZeroButton();
//		darkMode = scrollbar instanceof DarkScrollBar;
//
//		if (darkMode) {
//			return createZeroButton();
//		}
//
//		CustomButton btn = new CustomButton();
//		btn.setBackground(darkMode ? trackColor2 : trackColor1);
//		btn.setContentAreaFilled(false);
//		btn.setHorizontalAlignment(JButton.CENTER);
//		btn.setMargin(new Insets(0, 0, 0, 0));
//		btn.setBorderPainted(false);
//		if (orientation == SwingConstants.NORTH) {
//			btn.setIcon(ImageResource.getIcon("up.png", 10, 10));
//			btn.setPreferredSize(new Dimension(XDMUtils.getScaledInt(15), XDMUtils.getScaledInt(18)));
//		}
//		if (orientation == SwingConstants.SOUTH) {
//			btn.setIcon(ImageResource.getIcon("down.png", 10, 10));
//			btn.setPreferredSize(new Dimension(XDMUtils.getScaledInt(15), XDMUtils.getScaledInt(18)));
//		}
//		if (orientation == SwingConstants.EAST) {
//			btn.setIcon(ImageResource.getIcon("right.png", 10, 10));
//			btn.setPreferredSize(new Dimension(XDMUtils.getScaledInt(18), XDMUtils.getScaledInt(15)));
//		}
//		if (orientation == SwingConstants.WEST) {
//			btn.setIcon(ImageResource.getIcon("left.png", 10, 10));
//			btn.setPreferredSize(new Dimension(XDMUtils.getScaledInt(18), XDMUtils.getScaledInt(15)));
//		}
//		return btn;
	}
}
