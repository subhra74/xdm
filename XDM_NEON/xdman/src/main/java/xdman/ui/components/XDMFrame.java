package xdman.ui.components;

import java.awt.AWTEvent;
import java.awt.ActiveEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.MenuComponent;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowEvent;

import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import xdman.ui.res.ColorResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.XDMUtils;

public class XDMFrame extends JFrame implements ComponentListener {

	private static final long serialVersionUID = -8094995420106046965L;

	private JLayeredPane layeredPane;

	private boolean maximizeBox = true, minimizeBox = true;

	// private JButton menuBtn;

	private JPanel contentPane, modalPane, dialogPane;

	private static final int DEFAULT_LAYER = 0, MODAL_LAYER = 30, DIALOG_LAYER = 15;

	private Component lastFocusOwner;

	protected boolean showTwitterIcon, showFBIcon;

	protected String twitterUrl, fbUrl;

	public XDMFrame() {
		setUndecorated(true);
		createCursors();
		contentPane = new JPanel(new BorderLayout());
		modalPane = new LayeredPanel(150);
		modalPane.setVisible(false);
		dialogPane = new LayeredPanel(40);
		dialogPane.setVisible(false);
		createResizeGrip();
		panTitle = new TitlePanel(new BorderLayout(), this);
		panTitle.setBackground(ColorResource.getTitleColor());
		panTitle.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(0),
				XDMUtils.getScaledInt(5)));
		panTitle.setOpaque(true);
		registerTitlePanel(panTitle);
		panClient = new JPanel(new BorderLayout());
		panClient.setBackground(Color.WHITE);
		JPanel panContent = new JPanel(new BorderLayout());
		panContent.add(panTitle, BorderLayout.NORTH);
		panContent.add(panClient);
		contentPane.add(panContent);
		layeredPane = new JLayeredPane();
		layeredPane.add(contentPane, Integer.valueOf(DEFAULT_LAYER));
		layeredPane.add(modalPane, Integer.valueOf(MODAL_LAYER));
		layeredPane.add(dialogPane, Integer.valueOf(DIALOG_LAYER));
		super.add(layeredPane);
		super.addComponentListener(this);
	}

	public JPanel getTitlePanel() {
		return panTitle;
	}

	public void setMaximizeBox(boolean maximizeBox) {
		this.maximizeBox = maximizeBox;
	}

	public boolean isMaximizeBox() {
		return maximizeBox;
	}

	public void setMinimizeBox(boolean minimizeBox) {
		this.minimizeBox = minimizeBox;
	}

	public boolean isMinimizeBox() {
		return minimizeBox;
	}

	@Override
	public Component add(Component c) {
		return panClient.add(c);
	}

	JPanel panTitle, panClient;

	private JLabel lblRightGrip, lblLeftGrip, lblTopGrip, lblBottomGrip;

	private void createResizeGrip() {
		GripMouseAdapter gma = new GripMouseAdapter();
		lblRightGrip = new JLabel();
		lblRightGrip.setMaximumSize(new Dimension(2, lblRightGrip.getMaximumSize().height));
		lblRightGrip.setPreferredSize(new Dimension(2, lblRightGrip.getPreferredSize().height));
		lblRightGrip.setBackground(Color.BLACK);
		lblRightGrip.setOpaque(true);
		contentPane.add(lblRightGrip, BorderLayout.EAST);

		lblBottomGrip = new JLabel();
		lblBottomGrip.setMaximumSize(new Dimension(lblBottomGrip.getPreferredSize().width, 2));
		lblBottomGrip.setPreferredSize(new Dimension(lblBottomGrip.getPreferredSize().width, 2));
		lblBottomGrip.setBackground(Color.BLACK);
		lblBottomGrip.setOpaque(true);
		contentPane.add(lblBottomGrip, BorderLayout.SOUTH);

		lblLeftGrip = new JLabel();
		lblLeftGrip.setMaximumSize(new Dimension(2, lblLeftGrip.getPreferredSize().height));
		lblLeftGrip.setPreferredSize(new Dimension(2, lblLeftGrip.getPreferredSize().height));
		lblLeftGrip.setBackground(Color.BLACK);
		lblLeftGrip.setOpaque(true);
		contentPane.add(lblLeftGrip, BorderLayout.WEST);

		lblTopGrip = new JLabel();
		lblTopGrip.setMaximumSize(new Dimension(lblTopGrip.getPreferredSize().width, 2));
		lblTopGrip.setPreferredSize(new Dimension(lblTopGrip.getPreferredSize().width, 2));
		lblTopGrip.setBackground(Color.BLACK);
		lblTopGrip.setOpaque(true);
		contentPane.add(lblTopGrip, BorderLayout.NORTH);

		if (isResizable()) {

			lblTopGrip.addMouseListener(gma);

			lblTopGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int y = me.getYOnScreen();
					int diff = XDMFrame.this.getLocationOnScreen().y - y;
					XDMFrame.this.setLocation(XDMFrame.this.getLocation().x, me.getLocationOnScreen().y);
					XDMFrame.this.setSize(XDMFrame.this.getWidth(), XDMFrame.this.getHeight() + diff);
				}
			});

			lblRightGrip.addMouseListener(gma);

			lblRightGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int x = me.getXOnScreen();
					int diff = x - XDMFrame.this.getLocationOnScreen().x;
					XDMFrame.this.setSize(diff, XDMFrame.this.getHeight());
				}
			});

			lblLeftGrip.addMouseListener(gma);

			lblLeftGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int x = me.getXOnScreen();
					int diff = XDMFrame.this.getLocationOnScreen().x - x;
					XDMFrame.this.setLocation(me.getLocationOnScreen().x, XDMFrame.this.getLocation().y);
					XDMFrame.this.setSize(diff + XDMFrame.this.getWidth(), XDMFrame.this.getHeight());
				}
			});

			lblBottomGrip.addMouseListener(gma);

			lblBottomGrip.addMouseMotionListener(new MouseMotionAdapter() {
				@Override
				public void mouseDragged(MouseEvent me) {
					int y = me.getYOnScreen();
					int diff = y - XDMFrame.this.getLocationOnScreen().y;
					XDMFrame.this.setSize(XDMFrame.this.getWidth(), diff);
				}
			});
		}
	}

	int diffx, diffy;

	Box vBox;

	protected void registerTitlePanel(JPanel panel) {

		vBox = Box.createVerticalBox();
		vBox.setOpaque(true);
		vBox.setBackground(ColorResource.getTitleColor());
		Box hBox = Box.createHorizontalBox();
		hBox.setBackground(ColorResource.getTitleColor());

		// if (menuBox) {
		// // JButton btn2 =
		// createTransparentButton(ImageResource.get("exit.png"), new
		// Dimension(30, 30), null);
		// // btn2.setName("MENU_OPEN");
		// // // btn.setRolloverIcon(ImageResource.get("min_btn_r.png"));
		// // hBox.add(btn2);
		// // JButton btn =
		// createTransparentButton(ImageResource.get("drop.png"), new
		// Dimension(30, 30), null);
		// // btn.setName("MENU_OPEN");
		// // // btn.setRolloverIcon(ImageResource.get("min_btn_r.png"));
		// // hBox.add(btn);
		// // hBox.add(Box.createRigidArea(new Dimension(10, 1)));
		// // // hBox.add(Box.createHorizontalStrut(10));
		//
		// JLabel lbl = new JLabel();
		// lbl.setMaximumSize(new Dimension(1, 12));
		// lbl.setPreferredSize(new Dimension(1, 12));
		// lbl.setBackground(new Color(65,65,65));
		// lbl.setOpaque(true);
		// hBox.add(lbl);
		// hBox.add(Box.createRigidArea(new Dimension(10, 1)));
		// //menuBtn = btn;
		// // hBox.add(Box.createHorizontalStrut(10));
		// }
		if (showTwitterIcon) {
			JButton btnT = createTransparentButton(ImageResource.get("twitter.png"),
					new Dimension(XDMUtils.getScaledInt(30), XDMUtils.getScaledInt(30)), actTwitter);
			btnT.setToolTipText(StringResource.get("LBL_TWITTER_PAGE"));
			// btn.setRolloverIcon(ImageResource.get("max_btn_r.png"));
			hBox.add(btnT);
		}

		if (showFBIcon) {
			JButton btnF = createTransparentButton(ImageResource.get("facebook.png"),
					new Dimension(XDMUtils.getScaledInt(30), XDMUtils.getScaledInt(30)), actFb);
			btnF.setToolTipText(StringResource.get("LBL_LIKE_ON_FB"));
			// btn.setRolloverIcon(ImageResource.get("max_btn_r.png"));
			hBox.add(btnF);
		}

		if (minimizeBox) {
			JButton btn = createTransparentButton(ImageResource.get("title_min.png"),
					new Dimension(XDMUtils.getScaledInt(30), XDMUtils.getScaledInt(30)), actMin);
			// btn.setRolloverIcon(ImageResource.get("min_btn_r.png"));
			hBox.add(btn);
		}

		if (maximizeBox) {
			JButton btn = createTransparentButton(ImageResource.get("title_max.png"),
					new Dimension(XDMUtils.getScaledInt(30), XDMUtils.getScaledInt(30)), actMax);
			// btn.setRolloverIcon(ImageResource.get("max_btn_r.png"));
			hBox.add(btn);
		}

		JButton btn = createTransparentButton(ImageResource.get("title_close.png"),
				new Dimension(XDMUtils.getScaledInt(30), XDMUtils.getScaledInt(30)), actClose);
		// btn.setRolloverIcon(ImageResource.get("close_btn_r.png"));
		hBox.add(btn);

		vBox.add(hBox);
		vBox.add(Box.createVerticalGlue());

		panel.add(vBox, BorderLayout.EAST);
	}

	ActionListener actClose = new ActionListener() {
		public void actionPerformed(ActionEvent action) {
			XDMFrame.this.dispatchEvent(new WindowEvent(XDMFrame.this, WindowEvent.WINDOW_CLOSING));
		};
	};

	ActionListener actMax = new ActionListener() {
		public void actionPerformed(ActionEvent action) {
			XDMFrame.this
					.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
			XDMFrame.this.setExtendedState(
					(XDMFrame.this.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH ? JFrame.NORMAL
							: JFrame.MAXIMIZED_BOTH);
		};
	};

	ActionListener actMin = new ActionListener() {
		public void actionPerformed(ActionEvent action) {
			XDMFrame.this.setExtendedState(XDMFrame.this.getExtendedState() | JFrame.ICONIFIED);
		};
	};

	ActionListener actTwitter = new ActionListener() {

		@Override
		public void actionPerformed(ActionEvent e) {
			if (twitterUrl != null) {
				XDMUtils.browseURL(twitterUrl);
			}

		}
	}, actFb = new ActionListener() {

		@Override
		public void actionPerformed(ActionEvent e) {
			if (fbUrl != null) {
				XDMUtils.browseURL(fbUrl);
			}
		}
	};

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

	Cursor curDefault, curNResize, curEResize, curWResize, curSResize, curSEResize, curSWResize;

	private void createCursors() {
		curDefault = new Cursor(Cursor.DEFAULT_CURSOR);
		curNResize = new Cursor(Cursor.N_RESIZE_CURSOR);
		curWResize = new Cursor(Cursor.W_RESIZE_CURSOR);
		curEResize = new Cursor(Cursor.E_RESIZE_CURSOR);
		curSResize = new Cursor(Cursor.S_RESIZE_CURSOR);
	}

	JButton createTransparentButton(ImageIcon icon, Dimension d, ActionListener actionListener) {
		CustomButton btn = new CustomButton(icon);
		btn.setBackground(ColorResource.getTitleColor());
		btn.setBorderPainted(false);
		btn.setContentAreaFilled(false);
		btn.setFocusPainted(false);
		btn.setPreferredSize(d);
		btn.addActionListener(actionListener);
		return btn;
	}

//	protected void setMenuActionListener(ActionListener a) {
//		if (menuBtn != null) {
//			menuBtn.addActionListener(a);
//		}
//	}

	@Override
	public void componentHidden(ComponentEvent c) {

	}

	@Override
	public void componentMoved(ComponentEvent c) {

	}

	@Override
	public void componentResized(ComponentEvent c) {
		contentPane.setSize(super.getWidth(), super.getHeight());
		modalPane.setSize(super.getWidth(), super.getHeight());
		dialogPane.setSize(super.getWidth(), super.getHeight());
		revalidate();
	}

	@Override
	public void componentShown(ComponentEvent c) {

	}

	public void showModal(MessageBox component) {
		lastFocusOwner = getMostRecentFocusOwner();
		System.out.println("Last focus owner: " + lastFocusOwner);
		modalPane.add(component);
		component.setVisible(true);
		modalPane.setVisible(true);
		revalidate();
		component.selectDefaultButton();
		startModal(component);
	}

	public void hideModal(MessageBox component) {
		modalPane.remove(component);
		component.setVisible(false);
		modalPane.setVisible(false);
		revalidate();
		stopModal();
		if (lastFocusOwner == null) {
			requestFocusInWindow();
		} else {
			lastFocusOwner.requestFocusInWindow();
		}
	}

	public void showDialog(JComponent component) {
		dialogPane.removeAll();
		dialogPane.add(component);
		component.setVisible(true);
		dialogPane.setVisible(true);
		revalidate();
	}

	public void hideDialog(JComponent component) {
		dialogPane.remove(component);
		component.setVisible(false);
		dialogPane.setVisible(false);
		revalidate();
	}

	private synchronized void startModal(Component comp) {
		try {
			if (SwingUtilities.isEventDispatchThread()) {
				EventQueue theQueue = Toolkit.getDefaultToolkit().getSystemEventQueue();
				while (comp.isVisible()) {
					AWTEvent event = theQueue.getNextEvent();
					Object source = event.getSource();
					if (event instanceof ActiveEvent) {
						((ActiveEvent) event).dispatch();
					} else if (source instanceof Component) {
						((Component) source).dispatchEvent(event);
					} else if (source instanceof MenuComponent) {
						((MenuComponent) source).dispatchEvent(event);
					} else {
						System.err.println("Unable to dispatch: " + event);
					}
				}
			} else {
				while (comp.isVisible()) {
					wait();
				}
			}
		} catch (InterruptedException ignored) {
		}
	}

	private synchronized void stopModal() {
		// notifyAll();
	}
}