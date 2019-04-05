package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;

import xdman.Config;
import xdman.XDMApp;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class VideoPopup extends JDialog implements ActionListener, Comparator<VideoPopupItem> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -8181171604854521390L;
	private static VideoPopup _this;

	public static VideoPopup getInstance() {
		if (_this == null) {
			_this = new VideoPopup();
		}
		return _this;
	}

	public static boolean hasInstance() {
		return _this != null;
	}

	public synchronized void addVideo(HttpMetadata metadata, String file, String info) {
		VideoPopupItem item = new VideoPopupItem();
		item.setMetadata(metadata);
		item.setInfo(info);
		item.setFile(file);
		item.setTimestamp(System.currentTimeMillis());
		if (addItem(item)) {
			CustomButton cb = new CustomButton();
			cb.setHorizontalAlignment(JButton.LEFT);
			cb.setHorizontalTextPosition(JButton.LEFT);
			cb.setMargin(new Insets(0, 0, 0, 0));
			cb.setForeground(Color.WHITE);
			cb.setName(metadata.getId());
			cb.setText(item.toString());
			cb.setBackground(ColorResource.getDarkestBgColor());
			cb.setBorderPainted(false);
			cb.setFocusPainted(false);
			cb.setPreferredSize(new Dimension(getScaledInt(250), getScaledInt(30)));
			cb.setMinimumSize(new Dimension(getScaledInt(250), getScaledInt(30)));
			cb.setMaximumSize(new Dimension(getScaledInt(250), getScaledInt(30)));
			cb.addActionListener(this);
			menuBox.add(cb, 0);
			if (!isVisible()) {
				if (!Config.getInstance().isShowVideoListOnlyInBrowser()) {
					setVisible(true);
				}
			}
			revalidate();
			repaint();
		}
	}

	private void arrangeList() {
		videoItems.clear();
		ArrayList<VideoPopupItem> itemsCopy = new ArrayList<VideoPopupItem>();
		itemsCopy.addAll(XDMApp.getInstance().getVideoItemsList());
		Collections.sort(itemsCopy, this);
		for (VideoPopupItem item : itemsCopy) {
			videoItems.addElement(item);
		}
	}

	private VideoPopup() {
		videoItems = new DefaultListModel<VideoPopupItem>();
		init();
		Dimension d = Toolkit.getDefaultToolkit().getScreenSize();

		Insets scnMax = Toolkit.getDefaultToolkit().getScreenInsets(this.getGraphicsConfiguration());
		this.setLocation(d.width - getWidth() - getScaledInt(50),
				d.height - scnMax.bottom - getHeight() - getScaledInt(30));
	}

	private int initialY;
	private boolean expanded;
	private JPanel bottomPanel;
	private JPanel panel;
	private JButton closePopupBtn;
	private JPanel itemPanel;
	private boolean upward = false;
	private DefaultListModel<VideoPopupItem> videoItems;

	private JList<VideoPopupItem> itemListBox;
	private int mHoveredJListIndex = -1;
	private Box menuBox;

	private void init() {
		try {
			setUndecorated(true);
			setSize(getScaledInt(250), getScaledInt(40));
			setIconImage(ImageResource.get("icon.png").getImage());
			setFocusableWindowState(false);
			setType(Type.UTILITY);
			setAlwaysOnTop(true);

			try {
				if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
						.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
					if (!Config.getInstance().isNoTransparency()) {
						setOpacity(0.85f);
					}
				}
			} catch (Exception e) {
				Logger.log(e);
			}

			panel = new JPanel(new BorderLayout());
			panel.setBackground(ColorResource.getDarkestBgColor());
			add(panel);

			bottomPanel = new TitlePanel(new BorderLayout(), this);
			bottomPanel.setBorder(new EmptyBorder(0, getScaledInt(20), 0, 0));
			bottomPanel.setOpaque(true);
			bottomPanel.setBackground(ColorResource.getDarkerBgColor());
			panel.add(bottomPanel);

			JButton closeBtn = new CustomButton();
			closeBtn.setPreferredSize(new Dimension(getScaledInt(30), getScaledInt(40)));
			closeBtn.setMinimumSize(new Dimension(getScaledInt(30), getScaledInt(40)));
			closeBtn.setBackground(ColorResource.getDarkerBgColor());
			closeBtn.setBorderPainted(false);
			closeBtn.setFocusPainted(false);
			closeBtn.setName("CLOSE");

			closeBtn.setIcon(ImageResource.get("title_close.png"));
			closeBtn.setMargin(new Insets(0, 0, 0, 0));
			closeBtn.addActionListener(this);
			bottomPanel.add(closeBtn, BorderLayout.EAST);

			JButton popupBtn = new CustomButton();
			popupBtn.setBackground(ColorResource.getDarkerBgColor());
			popupBtn.setFont(FontResource.getItemFont());
			popupBtn.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(40)));
			popupBtn.setMinimumSize(new Dimension(getScaledInt(200), getScaledInt(40)));
			popupBtn.setBorderPainted(false);
			popupBtn.setForeground(Color.WHITE);
			popupBtn.setText("DOWNLOAD VIDEO");
			popupBtn.setFocusPainted(false);
			popupBtn.setName("EXPAND");
			popupBtn.addActionListener(this);
			bottomPanel.add(popupBtn);

			itemPanel = new JPanel(new BorderLayout());
			// itemPanel.setBorder(new EmptyBorder(5, 5, 5, 5));
			itemPanel.setOpaque(false);

			itemListBox = new JList<VideoPopupItem>(videoItems);
			itemListBox.setOpaque(false);
			itemListBox.setCellRenderer(new SimpleListRenderer());
			itemListBox.addMouseMotionListener(new MouseAdapter() {
				public void mouseMoved(MouseEvent me) {
					Point p = new Point(me.getX(), me.getY());
					int index = itemListBox.locationToIndex(p);
					if (index != mHoveredJListIndex) {
						mHoveredJListIndex = index;
						if (mHoveredJListIndex != -1) {
							itemListBox.setSelectedIndex(mHoveredJListIndex);
						} else {
							itemListBox.clearSelection();
						}
					}
				}

				@Override
				public void mouseExited(MouseEvent e) {
					itemListBox.clearSelection();
					itemListBox.repaint();
				}
			});

			JScrollPane jsp = new JScrollPane();
			jsp.setOpaque(false);
			jsp.setBorder(null);
			jsp.getViewport().setOpaque(false);

			DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
			jsp.setVerticalScrollBar(scrollBar);
			jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			jsp.getVerticalScrollBar().setUnitIncrement(getScaledInt(10));
			jsp.getVerticalScrollBar().setBlockIncrement(getScaledInt(25));

			menuBox = Box.createVerticalBox();
			menuBox.add(Box.createVerticalGlue());

			jsp.setViewportView(menuBox);
			itemPanel.add(jsp);

			CustomButton btn = new CustomButton();
			btn.setText(StringResource.get("LBL_MORE_FORMAT"));
			btn.setName("MORE_FORMAT");
			btn.addActionListener(this);
			btn.setMargin(new Insets(0, 0, 0, 0));
			btn.setForeground(Color.WHITE);
			btn.setBackground(ColorResource.getDarkestBgColor());
			btn.setBorderPainted(false);
			btn.setFocusPainted(false);
			itemPanel.add(btn, BorderLayout.SOUTH);

			closePopupBtn = new CustomButton();
			closePopupBtn.setBackground(ColorResource.getDarkerBgColor());
			closePopupBtn.setBorderPainted(false);
			closePopupBtn.setFocusPainted(false);
			closePopupBtn.setName("COLAPSE");

			closePopupBtn.addActionListener(this);

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// public static void main(String[] args) {
	// SwingUtilities.invokeLater(new Runnable() {
	//
	// @Override
	// public void run() {
	// new VideoPopup().setVisible(true);
	//
	// }
	// });
	//
	// }

	@Override
	public synchronized void actionPerformed(ActionEvent e) {
		String name = ((JComponent) e.getSource()).getName();
		for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
			if (name.equals(item.getMetadata().getId())) {
				collapse();
				HttpMetadata md = item.getMetadata().derive();
				Logger.log("dash metdata ? " + (md instanceof DashMetadata));
				XDMApp.getInstance().addVideo(md, item.getFile());
			}
		}
		if (name.equals("CLOSE")) {
			collapse();
			menuBox.removeAll();
			XDMApp.getInstance().getVideoItemsList().clear();
			dispose();
			_this = null;
		} else if (name.equals("COLAPSE")) {
			collapse();
		} else if (name.equals("EXPAND")) {
			if (!expanded) {
				expand();
			} else {
				collapse();
			}
		} else if (name.equals("MORE_FORMAT")) {
			JOptionPane.showMessageDialog(null, StringResource.get("LBL_FORMAT_HINT"));
		}
	}

	private void expand() {
		initialY = getLocationOnScreen().y;
		int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;

		int preferedExpandedHeight = getScaledInt(400);
		int bottomTaskbarHeight, topTaskbarHeight;
		Insets scnMax = Toolkit.getDefaultToolkit().getScreenInsets(this.getGraphicsConfiguration());
		bottomTaskbarHeight = scnMax.bottom;
		topTaskbarHeight = scnMax.top;

		upward = initialY > (screenHeight - (bottomTaskbarHeight + topTaskbarHeight)) / 2;

		arrangeList();
		panel.remove(bottomPanel);
		panel.add(itemPanel);

		if (upward) {
			if (getY() - topTaskbarHeight < preferedExpandedHeight) {
				preferedExpandedHeight = getY() - topTaskbarHeight;
			}
			setLocation(getX(), initialY - preferedExpandedHeight + getHeight());
			panel.add(bottomPanel, BorderLayout.SOUTH);
			itemPanel.add(closePopupBtn, BorderLayout.NORTH);
			closePopupBtn.setIcon(ImageResource.get("down.png"));

		} else {
			if (screenHeight - getY() - bottomTaskbarHeight < preferedExpandedHeight) {
				preferedExpandedHeight = screenHeight - getY() - bottomTaskbarHeight;
			}
			panel.add(bottomPanel, BorderLayout.NORTH);
			itemPanel.add(closePopupBtn, BorderLayout.SOUTH);
			closePopupBtn.setIcon(ImageResource.get("up.png"));
		}
		setSize(getWidth(), preferedExpandedHeight);
		revalidate();
		repaint();
		expanded = true;
	}

	private void collapse() {
		panel.remove(bottomPanel);
		panel.remove(itemPanel);
		int height = getHeight();
		int locationY = getY() + height - getScaledInt(40);
		setSize(getWidth(), getScaledInt(40));
		if (upward) {
			setLocation(getX(), locationY);
		}

		panel.add(bottomPanel);
		revalidate();
		repaint();
		expanded = false;
		upward = false;
		Logger.log("Closed");
	}

	@Override
	public int compare(VideoPopupItem item1, VideoPopupItem item2) {
		int ret = 0;
		if (item1.getTimestamp() > item2.getTimestamp()) {
			ret = 1;
		} else if (item1.getTimestamp() < item2.getTimestamp()) {
			ret = 1;
		}
		return upward ? ret : -ret;
	}

	private boolean addItem(VideoPopupItem item) {
		if (item.getMetadata() == null) {
			return false;
		}
		for (int i = 0; i < XDMApp.getInstance().getVideoItemsList().size(); i++) {
			VideoPopupItem p = XDMApp.getInstance().getVideoItemsList().get(i);
			HttpMetadata m1 = item.getMetadata();
			HttpMetadata m2 = p.getMetadata();
			if (m1.getType() == m2.getType()) {
				if (m1.getUrl().equals(m2.getUrl())) {
					if (m1 instanceof DashMetadata) {
						DashMetadata dm1 = (DashMetadata) m1;
						DashMetadata dm2 = (DashMetadata) m2;
						if (dm1.getUrl2().equals(dm2.getUrl2())) {
							return false;
						}
					} else {
						return false;
					}
				}
			}
		}

		XDMApp.getInstance().getVideoItemsList().add(item);
		return true;
	}

	public void updateYoutubeTitle(String url, String title) {
		if (title == null)
			return;
		String[] arr = getIdAndLen(url);
		String id = arr[0];
		String len = arr[1];
		for (int i = 0; i < XDMApp.getInstance().getVideoItemsList().size(); i++) {
			VideoPopupItem p = XDMApp.getInstance().getVideoItemsList().get(i);
			HttpMetadata m2 = p.getMetadata();
			if (m2 instanceof DashMetadata) {
				DashMetadata dm1 = (DashMetadata) m2;
				if (isSameURL(dm1.getUrl(), id, len) || isSameURL(dm1.getUrl2(), id, len)) {
					Logger.log("Updating title: " + title);
					updateFileName(title, p);
					updateButtonTitle(m2.getId(), p.toString());
				}
			}
		}
	}

	private void updateFileName(String file, VideoPopupItem p) {
		String oldFile = p.getFile();
		String ext = XDMUtils.getExtension(oldFile);
		p.setFile(file + (StringUtils.isNullOrEmptyOrBlank(ext) ? "" : ext));
		Logger.log("renaming: " + oldFile + " to: " + file);
	}

	private void updateButtonTitle(String id, String title) {
		for (int i = 0; i < menuBox.getComponentCount(); i++) {
			Component c = menuBox.getComponent(i);
			if (c instanceof JButton) {
				JButton jc = (JButton) c;
				if (id.equals(jc.getName())) {
					jc.setText(title);
					return;
				}
			}
		}
	}

	private String[] getIdAndLen(String url) {
		int index = url.indexOf("?");
		String query = url.substring(index + 1);
		String arr[] = query.split("&");
		String clen = null, id = null;
		for (int i = 0; i < arr.length; i++) {
			String str = arr[i];
			index = str.indexOf("=");
			if (index > 0) {
				String key = str.substring(0, index).trim();
				String val = str.substring(index + 1).trim();
				if (key.startsWith("range")) {
					continue;
				}
				if (key.equals("clen")) {
					clen = val;
				}
				if (str.startsWith("id")) {
					id = val;
				}
			}
		}
		return new String[] { id, clen };
	}

	private boolean isSameURL(String url, String id, String clen) {
		try {
			if (url != null) {
				return url.contains(id) && url.contains(clen);
			}
		} catch (Exception e) {
		}

		return false;
	}
}
