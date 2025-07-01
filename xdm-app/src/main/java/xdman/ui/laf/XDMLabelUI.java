package xdman.ui.laf;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicLabelUI;

public class XDMLabelUI extends BasicLabelUI {
	
	public static ComponentUI createUI(JComponent c) {
		return new XDMLabelUI();
	}
	
	@Override
	public void paint(Graphics g, JComponent c) {
		Graphics2D g2 = (Graphics2D) g;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		super.paint(g2, c);
	}
	
//	@Override
//	public void paint(Graphics g, JComponent c) {
//		System.out.println("called label paint");
//		Graphics2D g2d = (Graphics2D) g;
//		Toolkit tk = Toolkit.getDefaultToolkit();
//		Map map = (Map) (tk.getDesktopProperty("awt.font.desktophints"));
//		if (map != null) {
//			g2d.addRenderingHints(map);
//		}
//		super.paint(g2d, c);
//	}
}
