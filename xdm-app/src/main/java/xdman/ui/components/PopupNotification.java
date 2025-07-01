package xdman.ui.components;

import javax.swing.JButton;
import javax.swing.JFrame;
import static xdman.util.XDMUtils.getScaledInt;
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
