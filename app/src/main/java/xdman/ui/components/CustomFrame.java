package xdman.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;

import xdman.ui.components.XDMFrame.GripMouseAdapter;

public class CustomFrame extends JFrame {
	private boolean maximizeBox = true, minimizeBox = true;
	private Cursor curDefault, curNResize, curEResize, curWResize, curSResize, curSEResize, curSWResize;
	private JLabel lblRightGrip, lblLeftGrip, lblTopGrip, lblBottomGrip;
	private int diffx, diffy;
	private JPanel panTitle, panClient;

	public CustomFrame() {
		setUndecorated(true);
		createCursors();
		createResizeGrip();
	}

	@Override
	public Component add(Component c) {
		return this.add(c);
	}

	private void createResizeGrip() {
		GripMouseAdapter gma = new GripMouseAdapter();
		lblRightGrip = new JLabel();
		lblRightGrip.setMaximumSize(new Dimension(2, lblRightGrip.getMaximumSize().height));
		lblRightGrip.setPreferredSize(new Dimension(2, lblRightGrip.getPreferredSize().height));
		lblRightGrip.setBackground(Color.BLACK);
		lblRightGrip.setOpaque(true);
		add(lblRightGrip, BorderLayout.EAST);

		lblBottomGrip = new JLabel();
		lblBottomGrip.setMaximumSize(new Dimension(lblBottomGrip.getPreferredSize().width, 2));
		lblBottomGrip.setPreferredSize(new Dimension(lblBottomGrip.getPreferredSize().width, 2));
		lblBottomGrip.setBackground(Color.BLACK);
		lblBottomGrip.setOpaque(true);
		add(lblBottomGrip, BorderLayout.SOUTH);

		lblLeftGrip = new JLabel();
		lblLeftGrip.setMaximumSize(new Dimension(2, lblLeftGrip.getPreferredSize().height));
		lblLeftGrip.setPreferredSize(new Dimension(2, lblLeftGrip.getPreferredSize().height));
		lblLeftGrip.setBackground(Color.BLACK);
		lblLeftGrip.setOpaque(true);
		add(lblLeftGrip, BorderLayout.WEST);

		lblTopGrip = new JLabel();
		lblTopGrip.setMaximumSize(new Dimension(lblTopGrip.getPreferredSize().width, 2));
		lblTopGrip.setPreferredSize(new Dimension(lblTopGrip.getPreferredSize().width, 2));
		lblTopGrip.setBackground(Color.BLACK);
		lblTopGrip.setOpaque(true);
		add(lblTopGrip, BorderLayout.NORTH);

		if (isResizable()) {

			lblTopGrip.addMouseListener(gma);

			lblTopGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int y = me.getYOnScreen();
					int diff = CustomFrame.this.getLocationOnScreen().y - y;
					CustomFrame.this.setLocation(CustomFrame.this.getLocation().x, me.getLocationOnScreen().y);
					CustomFrame.this.setSize(CustomFrame.this.getWidth(), CustomFrame.this.getHeight() + diff);
				}
			});

			lblRightGrip.addMouseListener(gma);

			lblRightGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int x = me.getXOnScreen();
					int diff = x - CustomFrame.this.getLocationOnScreen().x;
					CustomFrame.this.setSize(diff, CustomFrame.this.getHeight());
				}
			});

			lblLeftGrip.addMouseListener(gma);

			lblLeftGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int x = me.getXOnScreen();
					int diff = CustomFrame.this.getLocationOnScreen().x - x;
					CustomFrame.this.setLocation(me.getLocationOnScreen().x, CustomFrame.this.getLocation().y);
					CustomFrame.this.setSize(diff + CustomFrame.this.getWidth(), CustomFrame.this.getHeight());
				}
			});

			lblBottomGrip.addMouseListener(gma);

			lblBottomGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int y = me.getYOnScreen();
					int diff = y - CustomFrame.this.getLocationOnScreen().y;
					CustomFrame.this.setSize(CustomFrame.this.getWidth(), diff);
				}
			});
		}
	}

	class GripMouseAdapter extends MouseAdapter {
		@Override
		public void mouseEntered(MouseEvent me) {
			if (me.getSource() == lblBottomGrip) {
				lblBottomGrip.setCursor(curSResize);
			} else if (me.getSource() == lblRightGrip) {
				lblRightGrip.setCursor(curEResize);
			} else if (me.getSource() == lblLeftGrip) {
				lblLeftGrip.setCursor(curWResize);
			} else if (me.getSource() == lblTopGrip) {
				lblTopGrip.setCursor(curNResize);
			}
		}

		@Override
		public void mouseExited(MouseEvent me) {
			((JLabel) me.getSource()).setCursor(curDefault);
		}

	}

	private void createCursors() {
		curDefault = new Cursor(Cursor.DEFAULT_CURSOR);
		curNResize = new Cursor(Cursor.N_RESIZE_CURSOR);
		curWResize = new Cursor(Cursor.W_RESIZE_CURSOR);
		curEResize = new Cursor(Cursor.E_RESIZE_CURSOR);
		curSResize = new Cursor(Cursor.S_RESIZE_CURSOR);
	}
}
