package xdm.ui.components;

import static xdm.core.util.XDMUtils.getScaledInt;

import javax.swing.JButton;
import javax.swing.JFrame;

public class PopupNotification extends JFrame {
	/**
	 * 
	 */
	private static final long serialVersionUID = 6092966602850444798L;

	PopupNotification() {
		setFocusableWindowState(false);
		setAlwaysOnTop(true);
		add(new JButton("test"));
		setSize(getScaledInt(300), getScaledInt(100));
		setVisible(true);
	}
}
