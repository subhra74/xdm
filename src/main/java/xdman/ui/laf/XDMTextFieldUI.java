package xdman.ui.laf;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextFieldUI;
import javax.swing.text.JTextComponent;

import xdman.ui.components.PopupAdapter;

public class XDMTextFieldUI extends BasicTextFieldUI {
	PopupAdapter popupAdapter;

	public static ComponentUI createUI(JComponent c) {
		return new XDMTextFieldUI();
	}

	@Override
	public void uninstallUI(JComponent c) {
		super.uninstallUI(c);
		this.popupAdapter.uninstall();
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		popupAdapter = new PopupAdapter((JTextComponent) c);
	}
}
