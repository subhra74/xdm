package xdman.ui.components;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

public class TitlePanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -6469773600360331175L;
	Component parentWindow;
	int diffx, diffy;

	public TitlePanel(Component w) {
		super();
		parentWindow = w;
		registerMouseListener();
	}

	public TitlePanel(LayoutManager lm, Window w) {
		super(lm);
		parentWindow = w;
		registerMouseListener();
	}

	public void registerMouseListener() {
		this.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				diffx = me.getXOnScreen()
						- parentWindow.getLocationOnScreen().x;
				diffy = me.getYOnScreen()
						- parentWindow.getLocationOnScreen().y;
			}
		});

		this.addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent me) {
				parentWindow.setLocation(me.getXOnScreen() - diffx,
						me.getYOnScreen() - diffy);
			}
		});
	}
}
