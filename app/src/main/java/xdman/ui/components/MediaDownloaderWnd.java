package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellEditor;

import xdman.Config;
import xdman.XDMApp;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.network.http.HttpHeader;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.XDMUtils;
import xdman.videoparser.ThumbnailDownloader;
import xdman.videoparser.ThumbnailListener;
import xdman.videoparser.YdlResponse;
import xdman.videoparser.YdlResponse.YdlMediaFormat;
import xdman.videoparser.YdlResponse.YdlVideo;
import xdman.videoparser.YoutubeDLHandler;

public class MediaDownloaderWnd extends JFrame implements ActionListener, ThumbnailListener, MediaImageSource {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6022330235790677598L;
	private JTextField txtURL;
	JButton btnDwn, btnBack;
	JButton btnStart;
	JProgressBar prg;
	JScrollPane jsp;
	private boolean stop;
	JLabel lineLbl2;
	// DefaultMutableTreeNode rootNode;
	// JTree tree;
	YoutubeDLHandler ydl;
	private VideoTableModel model;
	private JTable table;
	private long instancekey;
	private Map<String, ImageIcon> imageMap;
	private ThumbnailDownloader thumbnailDownloader;
	private JCheckBox chkSelectAll;
	private JLabel lblUser, lblPass;
	private JTextField txtUser, txtPassword;
	private JCheckBox chkAdvanced;

	public MediaDownloaderWnd() {
		initUI();
		imageMap = new HashMap<>();
		try {
			URL url = new URL(XDMUtils.getClipBoardText());
			txtURL.setText(url.toString());
			txtURL.setCaretPosition(0);
		} catch (Exception e) {
			Logger.log(e);
		}
	}

	private void initUI() {
		setUndecorated(true);

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

		setTitle(StringResource.get("TITLE_DOWN_VID"));
		setIconImage(ImageResource.get("icon.png").getImage());
		setSize(getScaledInt(500), getScaledInt(420));
		setLocationRelativeTo(null);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getWidth(), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getWidth() - getScaledInt(35), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.get("title_close.png"));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("TITLE_DOWN_VID"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getWidth(), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		add(titlePanel);

		int y = getScaledInt(55);

		int y1 = y;

		int h = getScaledInt(30);
		y += getScaledInt(15);

		prg = new JProgressBar();
		prg.setIndeterminate(true);
		prg.setBounds(getScaledInt(15), y, getWidth() - getScaledInt(30), getScaledInt(5));
		prg.setBorder(null);
		prg.setVisible(false);
		add(prg);

		txtURL = new JTextField();
		//PopupAdapter.registerTxtPopup(txtURL);
		txtURL.setBounds(getScaledInt(15), y, getWidth() - getScaledInt(30) - getScaledInt(110), h);
		add(txtURL);

		btnStart = createButton("BTN_SEARCH_VIDEO");
		btnStart.setBounds(getWidth() - getScaledInt(15) - getScaledInt(100), y, getScaledInt(100), h);
		btnStart.setName("START");
		add(btnStart);

		chkAdvanced = new JCheckBox(StringResource.get("SETTINGS_ADV"));
		chkAdvanced.setName("LBL_USER_PASS");
		chkAdvanced.setBackground(ColorResource.getDarkestBgColor());
		chkAdvanced.setIcon(ImageResource.get("unchecked.png"));
		chkAdvanced.setSelectedIcon(ImageResource.get("checked.png"));
		chkAdvanced.addActionListener(this);
		chkAdvanced.setForeground(Color.WHITE);
		chkAdvanced.setFocusPainted(false);
		chkAdvanced.setBounds(getScaledInt(15), y + h + getScaledInt(10), getWidth(), getScaledInt(30));
		add(chkAdvanced);

		lblUser = new JLabel(StringResource.get("DESC_USER"));
		lblUser.setBounds(getScaledInt(15), y + h + getScaledInt(10) + getScaledInt(30), getWidth(), getScaledInt(30));
		lblUser.setVisible(false);
		add(lblUser);
		txtUser = new JTextField();
		txtUser.setBounds(getScaledInt(15), y + h + getScaledInt(10) + getScaledInt(60), getScaledInt(200),
				getScaledInt(30));
		txtUser.setVisible(false);
		add(txtUser);
		lblPass = new JLabel(StringResource.get("DESC_PASS"));
		lblPass.setBounds(getScaledInt(15), y + h + getScaledInt(10) + getScaledInt(90), getWidth(), getScaledInt(30));
		lblPass.setVisible(false);
		add(lblPass);
		txtPassword = new JPasswordField();
		txtPassword.setBounds(getScaledInt(15), y + h + getScaledInt(10) + getScaledInt(120), getScaledInt(200),
				getScaledInt(30));
		txtPassword.setVisible(false);
		add(txtPassword);

		VideoDownloadItem item1 = new VideoDownloadItem();
		item1.title = "First item for text test";
		item1.desc = "Sample description for text tesing description";
		h = getScaledInt(300);

		model = new VideoTableModel();
		table = new JTable(model);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.setRowHeight(getScaledInt(125));
		table.setShowGrid(false);
		table.setOpaque(false);
		table.setBorder(null);
		table.setShowHorizontalLines(false);
		table.setShowVerticalLines(false);
		table.setDefaultRenderer(VideoItemWrapper.class, new VideoItemRenderer(this));
		table.setDefaultEditor(VideoItemWrapper.class, new VideoItemEditor(this));
		table.setTableHeader(null);

		// rootNode = new DefaultMutableTreeNode("Videos");
		// tree = new JTree(rootNode);
		// tree.setOpaque(false);

		jsp = new JScrollPane();
		// jsp = new JScrollPane(list);
		jsp.setBounds(0, y1, getWidth(), h + getScaledInt(15));
		jsp.setBorder(new EmptyBorder(0, 0, 0, 0));
		jsp.getViewport().setBorder(null);
		jsp.getViewport().setOpaque(false);
		// jsp.setViewportView(tree);
		jsp.setViewportView(table);
		jsp.setOpaque(false);
		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		add(jsp);
		jsp.setVisible(false);

		y += h;

		lineLbl2 = new JLabel();
		lineLbl2.setBackground(ColorResource.getDarkBgColor());
		lineLbl2.setBounds(0, y, getWidth(), 1);
		lineLbl2.setOpaque(true);
		lineLbl2.setVisible(false);
		add(lineLbl2);

		y += getScaledInt(10);
		h = getScaledInt(30);
		btnDwn = createButton("LBL_DOWNLOAD");
		btnDwn.setBounds(getWidth() - getScaledInt(15) - getScaledInt(120), y, getScaledInt(120), h);
		btnDwn.setName("DOWNLOAD");
		btnDwn.setVisible(false);
		add(btnDwn);

		chkSelectAll = new JCheckBox(StringResource.get("LBL_SELECT_ALL"));
		chkSelectAll.setBackground(ColorResource.getDarkestBgColor());
		chkSelectAll.setIcon(ImageResource.get("unchecked.png"));
		chkSelectAll.setSelectedIcon(ImageResource.get("checked.png"));
		chkSelectAll.addActionListener(this);
		chkSelectAll.setForeground(Color.WHITE);
		chkSelectAll.setFocusPainted(false);

		chkSelectAll.setBounds(getScaledInt(120), y + getScaledInt(5), getScaledInt(150), getScaledInt(20));
		chkSelectAll.setVisible(false);
		chkSelectAll.setName("SELECT_ALL");

		add(chkSelectAll);
		// btnQ = createButton("BTN_DOWNLOAD_LATER");
		// btnQ.setBounds(getWidth() - 15 - 150 - 160, y, 150, h);
		// btnQ.setName("BTN_Q");
		// add(btnQ);
		// btnQ.setVisible(false);

		btnBack = createButton("BTN_BACK");
		btnBack.setBounds(getScaledInt(15), y, getScaledInt(100), h);
		btnBack.setName("BACK");
		add(btnBack);
		btnBack.setVisible(false);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowActivated(WindowEvent e) {
				txtURL.requestFocus();
			}

			@Override
			public void windowClosing(WindowEvent e) {

			}
		});

	}

	private JButton createButton(String name) {
		JButton btn = new CustomButton(StringResource.get(name));
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getNormalFont());
		btn.addActionListener(this);
		return btn;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		JComponent c = (JComponent) e.getSource();
		String name = c.getName();
		if ("START".equals(name)) {
			imageMap.clear();
			instancekey = System.currentTimeMillis();
			model.clear();
			if (txtURL.getText().length() < 1) {
				JOptionPane.showMessageDialog(this, StringResource.get("MSG_NO_URL"));
				return;
			}
			if (!XDMUtils.checkComponentsInstalled()) {
				JOptionPane.showMessageDialog(this, StringResource.get("LBL_COMPONENT_MISSING"));
				return;
			}
			if (!txtURL.getText().startsWith("http")) {
				txtURL.setText("http://" + txtURL.getText());
			}
			jsp.setVisible(false);
			prg.setVisible(true);
			btnDwn.setVisible(true);
			btnDwn.setText(StringResource.get("BTN_STOP_PROCESSING"));
			chkSelectAll.setVisible(false);
			chkAdvanced.setVisible(false);
			txtUser.setVisible(false);
			txtPassword.setVisible(false);
			lblUser.setVisible(false);
			lblPass.setVisible(false);
			btnDwn.setName("STOP");
			btnStart.setVisible(false);
			txtURL.setVisible(false);
			lineLbl2.setVisible(false);
			stop = false;
			getVideoItems(txtURL.getText());
		}
		if ("DOWNLOAD".equals(name)) {
			table.getDefaultEditor(YdlVideo.class).stopCellEditing();
			downloadVideo();
		}
		if ("CLOSE".equals(name)) {
			stop();
			dispose();
		}
		if ("STOP".equals(name)) {
			stop();
		}
		if ("BACK".equals(name)) {
			if (table.isEditing()) {
				table.getCellEditor().stopCellEditing();
			}
			// table.getDefaultEditor(YdlVideo.class).stopCellEditing();
			model.clear();
			prg.setVisible(false);
			txtURL.setVisible(true);
			chkAdvanced.setVisible(true);
			btnStart.setVisible(true);
			btnDwn.setName("DOWNLOAD");
			btnDwn.setText(StringResource.get("LBL_DOWNLOAD"));
			chkSelectAll.setVisible(false);
			btnDwn.setVisible(false);
			txtUser.setVisible(chkAdvanced.isSelected());
			txtPassword.setVisible(chkAdvanced.isSelected());
			lblUser.setVisible(chkAdvanced.isSelected());
			lblPass.setVisible(chkAdvanced.isSelected());

			// btnQ.setVisible(false);
			jsp.setVisible(false);
			lineLbl2.setVisible(false);
			btnBack.setVisible(false);
			imageMap.clear();
			if (thumbnailDownloader != null) {
				thumbnailDownloader.stop();
			}
		}
		if ("SELECT_ALL".equals(name)) {
			if (table.isEditing()) {
				TableCellEditor editor = table.getCellEditor(); // .stopCellEditing();
				if (editor != null) {
					editor.stopCellEditing();
				}
			}
			for (int i = 0; i < model.getRowCount(); i++) {
				VideoItemWrapper wp = (VideoItemWrapper) model.getValueAt(i, 0);
				wp.checked = chkSelectAll.isSelected();
			}
			model.fireTableDataChanged();
		}
		if ("LBL_USER_PASS".equals(name)) {
			boolean show = chkAdvanced.isSelected();
			lblUser.setVisible(show);
			lblPass.setVisible(show);
			txtUser.setVisible(show);
			txtPassword.setVisible(show);
			if (!show) {
				txtUser.setText("");
				txtPassword.setText("");
			}
		}
	}

	private void stop() {
		prg.setVisible(false);
		txtURL.setVisible(true);
		btnStart.setVisible(true);
		btnDwn.setName("DOWNLOAD");
		btnDwn.setText(StringResource.get("LBL_DOWNLOAD"));
		btnDwn.setVisible(false);
		// btnQ.setVisible(false);
		jsp.setVisible(false);
		btnBack.setVisible(false);
		stop = true;
		if (ydl != null) {
			ydl.stop();
		}
		imageMap.clear();
		if (thumbnailDownloader != null) {
			thumbnailDownloader.stop();
		}
	}

	private void onVideoListReady() {
		btnStart.setVisible(false);
		txtURL.setVisible(false);
		prg.setVisible(false);
		jsp.setVisible(true);
		lineLbl2.setVisible(true);
		btnDwn.setName("DOWNLOAD");
		chkSelectAll.setVisible(true);
		chkSelectAll.setSelected(true);
		btnDwn.setText(StringResource.get("LBL_DOWNLOAD"));
		// http://demo.unified-streaming.com/video/tears-of-steel/tears-of-steel.mp4/.m3u8
		btnDwn.setVisible(true);
		btnBack.setVisible(true);

		ArrayList<String> thumbUrls = new ArrayList<>();
		for (int i = 0; i < model.getRowCount(); i++) {
			VideoItemWrapper wp = (VideoItemWrapper) model.getValueAt(i, 0);
			YdlVideo video = wp.videoItem;
			if (video.thumbnail != null) {
				thumbUrls.add(video.thumbnail);
			}
		}
		if (model.getRowCount() == 1) {
			VideoItemWrapper wp = (VideoItemWrapper) model.getValueAt(0, 0);
			wp.checked = true;
			model.fireTableDataChanged();
		}
		thumbnailDownloader = new ThumbnailDownloader(thumbUrls, this, instancekey);
		thumbnailDownloader.download();
		// if (table.getRowCount() > 0) {
		// table.setRowSelectionInterval(0, 0);
		// }
		// btnQ.setVisible(true);
	}

	private void getVideoItems(final String url) {
		new Thread() {
			@Override
			public void run() {
				try {
					// rootNode.removeAllChildren();
					ydl = new YoutubeDLHandler(url, txtUser.getText(), txtPassword.getText());
					// https://www.youtube.com/watch?v=PMR0ld5h938
					// "C:\\Users\\subhro\\Desktop\\ytdl\\youtube-dl.exe",
					// url);//
					// "https://www.youtube.com/user/koushks");//
					// "https://www.youtube.com/watch?v=Yv2xctJxE-w&list=PL4AFF701184976B25");
					// "http://demo.unified-streaming.com/video/tears-of-steel/tears-of-steel.ism/.m3u8");//
					// "https://www.youtube.com/watch?v=Yv2xctJxE-w&list=PL4AFF701184976B25");
					ydl.start();
					SwingUtilities.invokeAndWait(new Runnable() {
						@Override
						public void run() {
							ArrayList<VideoItemWrapper> list = new ArrayList<>();
							Logger.log("Total video found: " + ydl.getVideos().size());
							for (int i = 0; i < ydl.getVideos().size(); i++) {
								YdlVideo ydln = ydl.getVideos().get(i);
								if (ydln.mediaFormats == null || ydln.mediaFormats.size() < 1) {
									Logger.log("media formats not available");
									continue;
								}
								VideoItemWrapper wrapper = new VideoItemWrapper();
								wrapper.checked = true;
								wrapper.videoItem = ydln;
								list.add(wrapper);
								// DefaultMutableTreeNode node = new DefaultMutableTreeNode(ydln.title);
								// rootNode.add(node);
								// for (int j = 0; j < ydln.mediaFormats.size(); j++) {
								// YdlMediaFormat fmt = ydln.mediaFormats.get(j);
								// DefaultMutableTreeNode node2 = new DefaultMutableTreeNode(fmt);
								// node.add(node2);
								// }
							}
							// if (rootNode.getChildCount() > 1) {
							// tree.expandRow(0);
							// } else {
							// tree.expandRow(0);
							// tree.expandRow(1);
							// }
							model.setList(list);
						}
					});
				} catch (Exception e) {
					Logger.log(e);
				}
				try {
					SwingUtilities.invokeAndWait(new Runnable() {

						@Override
						public void run() {
							if (!stop)
								onVideoListReady();

						}
					});
				} catch (InvocationTargetException |

						InterruptedException e) {
					Logger.log(e);
				}
			}
		}.start();
	}

	private VideoWrapper createDownloadData(YdlVideo video) {
		YdlMediaFormat fmt = video.mediaFormats.get(video.index);
		String title = video.title;
		String file = XDMUtils.getFileName(title) + "." + fmt.ext;
		HttpMetadata md = null;
		switch (fmt.type) {
		case YdlResponse.DASH_HTTP:
			DashMetadata dm = new DashMetadata();
			dm.setYdlUrl(txtURL.getText());
			dm.setUrl(fmt.videoSegments[0]);
			dm.setUrl2(fmt.audioSegments[0]);
			for (HttpHeader header : fmt.headers) {
				dm.getHeaders().addHeader(header);
			}
			for (HttpHeader header : fmt.headers2) {
				dm.getHeaders2().addHeader(header);
			}
			md = dm;
			break;
		case YdlResponse.HLS:
			md = new HlsMetadata();
			md.setYdlUrl(txtURL.getText());
			md.setUrl(fmt.url);
			for (HttpHeader header : fmt.headers) {
				md.getHeaders().addHeader(header);
			}
			break;
		case YdlResponse.HDS:
			HdsMetadata hm = new HdsMetadata();
			hm.setYdlUrl(txtURL.getText());
			hm.setUrl(fmt.url);
			for (HttpHeader header : fmt.headers) {
				hm.getHeaders().addHeader(header);
			}
			md = hm;
			break;
		case YdlResponse.HTTP:
			HttpMetadata ht = new HttpMetadata();
			ht.setYdlUrl(txtURL.getText());
			ht.setUrl(fmt.url);
			for (HttpHeader header : fmt.headers) {
				ht.getHeaders().addHeader(header);
			}
			md = ht;
			break;
		}

		if (md != null) {
			VideoWrapper wp = new VideoWrapper();
			wp.md = md;
			wp.file = file;
			return wp;
		}
		return null;
	}

	private void downloadSingle(YdlVideo video) {
		VideoWrapper wp = createDownloadData(video);
		if (wp != null) {
			XDMApp.getInstance().addVideo(wp.md, wp.file);
		}
	}

	private void downloadBatch(ArrayList<YdlVideo> items) {
		ArrayList<VideoWrapper> listWrap = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			VideoWrapper wp = createDownloadData(items.get(i));
			if (wp != null) {
				listWrap.add(wp);
			}
		}
		new BatchVideoWnd(listWrap).setVisible(true);
	}

	private void downloadVideo() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}

		ArrayList<YdlVideo> items = model.getSelectedVideoList();
		int selectedCount = items.size();
		if (selectedCount < 1)
			return;
		if (selectedCount == 1) {
			downloadSingle(items.get(0));
		} else {
			downloadBatch(items);
		}

		if (thumbnailDownloader != null) {
			thumbnailDownloader.stop();
		}
		dispose();
	}

	public void launchWithUrl(String url) {
		setVisible(true);
		txtURL.setText(url);
		btnStart.doClick();
	}

	class VideoWrapper {
		HttpMetadata md;
		String file;
	}

	@Override
	public void thumbnailsLoaded(long key, String url, String file) {
		// this key is changed on each time start button is clicked
		System.out.println("Thumbnail callback");
		if (this.instancekey == key) {
			for (int i = 0; i < model.getRowCount(); i++) {
				VideoItemWrapper wp = (VideoItemWrapper) model.getValueAt(i, 0);
				YdlVideo video = wp.videoItem;
				if (video.thumbnail != null && video.thumbnail.equals(url)) {
					ImageIcon ico = loadImage(file, video.duration);
					System.out.println("Icon: " + ico);
					imageMap.put(url, ico);
					model.fireTableCellUpdated(i, 0);
					break;
				}
			}
		} else {
			System.out.println("diff instance");
		}
	}

	private Image loadImage64(File file) throws IOException {
		BufferedImage img = ImageIO.read(file);
		Image img64 = img.getScaledInstance(getScaledInt(119), -1, Image.SCALE_SMOOTH);
		img.flush();
		return img64;

	}

	private ImageIcon loadImage(String file, long duration) {
		File f = new File(file);
		try {
			Image img = loadImage64(f);
			BufferedImage image = new BufferedImage(getScaledInt(119), getScaledInt(92), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = (Graphics2D) image.getGraphics();
			g.addRenderingHints(new RenderingHints(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY));
			g.addRenderingHints(new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			int h = img.getHeight(null);
			g.drawImage(img, 0, getScaledInt(92) / 2 - h / 2, null);
			if (duration > 0) {
				g.setFont(FontResource.getNormalFont());
				String sDuration = FormatUtilities.hms((int) duration);
				if (sDuration.length() > 0) {
					int textWidth = g.getFontMetrics().stringWidth(sDuration);
					int textHeight = g.getFontMetrics().getHeight();
					int y = getScaledInt(92) - g.getFontMetrics().getDescent();// - textHeight +
																				// g.getFontMetrics().getAscent() -
					// g.getFontMetrics().getDescent();
					g.fillRect(getScaledInt(119) - textWidth, getScaledInt(92) - textHeight, textWidth, textHeight);
					g.setColor(Color.WHITE);
					g.drawString(sDuration, getScaledInt(119) - textWidth, y);
				}
			}
			g.dispose();
			// scaledImg.flush();
			img.flush();
			return new ImageIcon(image);
		} catch (Exception e) {
			Logger.log(e);
			return null;
		} finally {
			System.out.println(f);
			f.delete();
		}
	}

	public ImageIcon getImage(String url) {
		return imageMap.get(url);
	}
}
