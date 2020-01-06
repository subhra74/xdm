package xdman.ui.laf;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;

public class XDMToolBarButtonUI extends BasicButtonUI {
	Color pressedColor = new Color(170, 170, 170), rolloverColor = new Color(
			180, 180, 180);

	protected void paintButtonNormal(Graphics g, AbstractButton b) {
		// g.setColor(b.getBackground());
		// g.fillRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
	}

	protected void paintButtonPressed(Graphics g, AbstractButton b) {

		Graphics2D g2 = (Graphics2D) g;

		g2.setColor(pressedColor);
		g2.fillRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
		// g2.setColor(Color.LIGHT_GRAY);
		// g2.drawRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
	}

	protected void paintButtonRollOver(Graphics g, AbstractButton b) {

		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(rolloverColor);
		g2.fillRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
		// g2.setColor(Color.LIGHT_GRAY);
		// g2.drawRect(0, 0, b.getWidth() - 1, b.getHeight() - 1);
	}

	public void paint(Graphics g, JComponent c) {
		AbstractButton b = (AbstractButton) c;
		ButtonModel bm = b.getModel();
		if (bm.isRollover()) {
			paintButtonRollOver(g, b);
		} else {
			paintButtonNormal(g, b);
		}
		super.paint(g, c);
	}

}
