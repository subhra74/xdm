package xdman.ui.laf;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;

import xdman.ui.components.CustomButton;
import xdman.ui.res.ColorResource;

public class XDMButtonUI extends BasicButtonUI {

	static XDMButtonUI buttonUI;

	public static ComponentUI createUI(JComponent c) {
		if (buttonUI == null) {
			buttonUI = new XDMButtonUI();
		}
		return buttonUI;
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		if (c instanceof JButton) {
			JButton btn = (JButton) c;
			if (!(c instanceof CustomButton)) {
				c.setForeground(Color.WHITE);
				c.setBackground(ColorResource.getButtonBackColor());
				btn.setBorderPainted(false);
			}
		}
	}

	protected void paintButtonNormal(Graphics g, AbstractButton b) {
		if (!b.isOpaque()) {
			Graphics2D g2 = (Graphics2D) g;
			g2.setPaint(b.getBackground());
			g2.fillRect(0, 0, b.getWidth(), b.getHeight());
		}
	}

	protected void paintButtonPressed(Graphics g, AbstractButton b) {
		Graphics2D g2 = (Graphics2D) g;
		g2.setColor(b.getBackground());
		// Color color = (Color) b.getClientProperty("xdmbutton.pressedcolor");
		// if (color != null) {
		// g2.setPaint(color);
		// } else {
		// g2.setPaint(Color.GRAY);
		// }
		g2.fillRect(0, 0, b.getWidth(), b.getHeight());
	}

	protected void paintButtonRollOver(Graphics g, AbstractButton b) {
		Graphics2D g2 = (Graphics2D) g;
		// if (b.getClientProperty("xdmbutton.grayrollover") != null) {
		// g2.setPaint(Color.DARK_GRAY);
		// } else {
		// g2.setPaint(ColorResource.getSelectionColor());
		// }
		g2.setColor(b.getBackground());
		g2.fillRect(0, 0, b.getWidth(), b.getHeight());
	}

	public void paint(Graphics g, JComponent c) {
		try {
			AbstractButton b = (AbstractButton) c;
			ButtonModel bm = b.getModel();
			if (bm.isRollover()) {
				paintButtonRollOver(g, b);
			} else {
				paintButtonNormal(g, b);
			}
			super.paint(g, c);
		} catch (Exception e) {
		}
	}
}
