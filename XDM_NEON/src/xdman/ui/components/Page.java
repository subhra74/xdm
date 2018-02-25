package xdman.ui.components;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;

import xdman.Config;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;


import static xdman.util.XDMUtils.getScaledInt;
public class Page extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -1452620326785609526L;
	private XDMFrame parent;
	private int diffx, diffy;
	private Color bgColor;
	protected JScrollPane jsp;
	private int y = 0, h = 0;
	private JLabel titleLbl, btnNav;
	private int width;
	private String title;

	public Page(String title, int width, XDMFrame parent) {
		setOpaque(false);
		setLayout(null);
		this.title = title;
		this.width = width;
		this.parent = parent;
		bgColor = new Color(0, 0, 0, Config.getInstance().isNoTransparency()?255:200);
		MouseInputAdapter ma = new MouseInputAdapter() {
		};

		addMouseListener(ma);
		addMouseMotionListener(ma);

		jsp = new JScrollPane();
		jsp.setOpaque(false);
		jsp.setBorder(null);
		jsp.getViewport().setOpaque(false);

		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		jsp.getVerticalScrollBar().setUnitIncrement(getScaledInt(10));
		jsp.getVerticalScrollBar().setBlockIncrement(getScaledInt(25));

		add(jsp);

		registerMouseListener();

		init();

	}

	private void init() {
		y = getScaledInt(25);
		h = getScaledInt(40);

		btnNav = new JLabel(ImageResource.get("back.png"));
		btnNav.setFont(FontResource.getBiggerFont());
		btnNav.setForeground(ColorResource.getSelectionColor());
		btnNav.setBounds(getScaledInt(10), y, getScaledInt(35), h);
		add(btnNav);

		btnNav.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				close();
			}
		});

		titleLbl = new JLabel(title);
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(50), y, getScaledInt(200), h);
		add(titleLbl);

		y += h;
		y += getScaledInt(10);
		h = getScaledInt(2);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, y, width, h);
		lineLbl.setOpaque(true);
		add(lineLbl);

		y += h;

	}

	protected void setBgColor(Color color) {
		this.bgColor = color;
	}

	public void registerMouseListener() {
		addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				diffx = me.getXOnScreen() - parent.getLocationOnScreen().x;
				diffy = me.getYOnScreen() - parent.getLocationOnScreen().y;
			}
		});

		addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent me) {
				parent.setLocation(me.getXOnScreen() - diffx, me.getYOnScreen() - diffy);
			}
		});
	}

	public void close() {
		parent.hideDialog(this);
	}

	public void showPanel() {
		int x = parent.getWidth() - width;
		jsp.setBounds(0, y, width, parent.getHeight() - y);
		setBounds(x, 0, width, parent.getHeight());
		parent.showDialog(this);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				jsp.getVerticalScrollBar().setValue(0);
			}
		});
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(bgColor);
		g.fillRect(0, 0, getWidth(), getHeight());
	}

	public void addToPage(Component c) {
		this.add(c);
	}

	protected XDMFrame getParentFrame() {
		return parent;
	}
}
