package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.font.LineMetrics;

import javax.swing.JComponent;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;

public class CircleProgressBar extends JComponent {
	private static final long serialVersionUID = 3778513245025142955L;
	private int padding = getScaledInt(4);

	public CircleProgressBar() {
		foreColor = ColorResource.getSelectionColor();
		backColor = ColorResource.getDarkBgColor();
	}

	@Override
	public void paint(Graphics g) {
		Graphics2D g2 = (Graphics2D) g;
		if (g2 == null) {
			return;
		}
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		// g2.setRenderingHint(RenderingHints.KEY_RENDERING,
		// RenderingHints.VALUE_RENDER_QUALITY);

		int sweep_angle = (int)(((float)value * 360) / 100);
		g2.setColor(Color.GRAY);
		g2.setStroke(stroke);
		g2.drawArc(padding, padding, getWidth() - 2 * padding, getHeight() - 2
				* padding, getScaledInt(90), -360);
		// g2.drawArc(2, 2, getWidth() - 12, getHeight() - 12, 90, -360);
		if (value > 0) {
			g2.setColor(foreColor);
			// g2.drawArc(2, 2, getWidth() - 12, getHeight() - 12, 90,
			// -sweep_angle);
			g2.drawArc(padding, padding, getWidth() - 2 * padding, getHeight()
					- 2 * padding, getScaledInt(90), -sweep_angle);
		}

		g2.setFont(FontResource.getItemFont());
		FontMetrics fm = g2.getFontMetrics();
		String str = value + "%";
		int w = (int) fm.getStringBounds(str, g2).getWidth();// fm.stringWidth(str);
		LineMetrics lm = fm.getLineMetrics(str, g2);
		int h = (int) (lm.getAscent() + lm.getDescent());
		g2.drawString(str, (getWidth()  - w) / 2,
				((getHeight()   + h) / 2) - lm.getDescent());
	}

	Stroke stroke = new BasicStroke(getScaledInt(4));
	private int value;

	Color foreColor, backColor;

	public void setValue(int value) {
		this.value = value;
		repaint();
	}

	public int getValue() {
		return value;
	}
}
