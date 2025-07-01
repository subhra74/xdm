package xdman.ui.laf;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicMenuItemUI;

import xdman.ui.res.ColorResource;
public class XDMMenuItemUI extends BasicMenuItemUI {
	Color colorSelect, colorBg;

	public static ComponentUI createUI(JComponent c) {
		return new XDMMenuItemUI();
	}

	public XDMMenuItemUI() {
		colorSelect = ColorResource.getSelectionColor();
		colorBg = ColorResource.getDarkerBgColor();// Color.WHITE;
	}

	@Override
	protected Dimension getPreferredMenuItemSize(JComponent c, Icon checkIcon,
			Icon arrowIcon, int defaultTextIconGap) {
		Dimension d = super.getPreferredMenuItemSize(c, checkIcon, arrowIcon,
				defaultTextIconGap);
		return new Dimension(d.width + getScaledInt(10), d.height);
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		c.setBorder(null);
		if (c instanceof AbstractButton) {
			AbstractButton btn = (AbstractButton) c;
			btn.setBorder(new EmptyBorder(getScaledInt(5), getScaledInt(10), getScaledInt(5), getScaledInt(10)));
			btn.setBorderPainted(false);
		}
	}

	protected void paintButtonPressed(Graphics g, AbstractButton b) {
		Color c = g.getColor();
		Graphics2D g2 = (Graphics2D) g;
		g2.setPaint(colorSelect);
		g2.fillRect(0, 0, b.getWidth(), b.getHeight());
		g.setColor(c);
	}

	@Override
	protected void paintBackground(Graphics g, JMenuItem menuItem, Color bgColor) {
		ButtonModel model = menuItem.getModel();
		Color oldColor = g.getColor();
		int menuWidth = menuItem.getWidth();
		int menuHeight = menuItem.getHeight();

		Color bgc = (Color) menuItem.getClientProperty("bgColor");
		if (bgc != null) {
			g.setColor(bgc);
		} else {
			g.setColor(colorBg);
		}
		g.fillRect(0, 0, menuWidth, menuHeight);

		if (model.isArmed()
				|| (menuItem instanceof JMenu && model.isSelected())) {
			paintButtonPressed(g, menuItem);
		} else {
			// if (menuItem.getIcon() != null) {
			// int gap = menuItem.getIcon().getIconWidth() + 2;
			// g.setColor(this.darkColor);
			// g.drawLine(gap, 0, gap, menuItem.getHeight());
			// g.setColor(this.lightColor);
			// g.drawLine(gap + 1, 0, gap + 1, menuItem.getHeight());
			// }
		}

		if (menuItem instanceof JCheckBoxMenuItem) {
			if (((JCheckBoxMenuItem) menuItem).isSelected()) {
				// chkIcon.paintIcon(menuItem, g, 5, 5);
			}
		}

		g.setColor(oldColor);
	}
	
//	@Override
//	public void paint(Graphics g, JComponent c) {
//		Graphics2D g2d=(Graphics2D) g;
//		Toolkit tk = Toolkit.getDefaultToolkit();
//		Map map = (Map)(tk.getDesktopProperty("awt.font.desktophints"));
//		if (map != null) {
//		    g2d.addRenderingHints(map);
//		}
//		super.paint(g, c);
//	}
}
