package xdman.ui.laf;

import java.awt.Graphics;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTreeUI;

import xdman.ui.res.ImageResource;

public class XDMTreeUI extends BasicTreeUI {

	public static ComponentUI createUI(JComponent c) {
		return new XDMTreeUI();
	}

	@Override
	public Icon getExpandedIcon() {
		return ImageResource.get("EXPAND_ICON");
	}

	@Override
	protected void paintHorizontalLine(Graphics arg0, JComponent arg1,
			int arg2, int arg3, int arg4) {
		// TODO Auto-generated method stub
		// super.paintHorizontalLine(arg0, arg1, arg2, arg3, arg4);
	}

	@Override
	protected void paintVerticalLine(Graphics arg0, JComponent arg1, int arg2,
			int arg3, int arg4) {
		// TODO Auto-generated method stub
		// super.paintVerticalLine(arg0, arg1, arg2, arg3, arg4);
	}

	@Override
	public Icon getCollapsedIcon() {
		return ImageResource.get("COLLAPSE_ICON");
	}
}
