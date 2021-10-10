/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

package xdman.ui.components;

import static xdman.util.XDMUtils.getScaledInt;

import java.awt.Color;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;

import org.tinylog.Logger;

import xdman.Config;
import xdman.DownloadQueue;
import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

@SuppressWarnings("FieldCanBeLocal")
public class NewDownloadWindow extends JDialog implements ActionListener, DocumentListener {

	private static final long serialVersionUID = 416356191545932172L;
	private JTextField txtURL;
	private XDMFileSelectionPanel filePane;
	private JPopupMenu pop;
	private CustomButton btnMore, btnDN, btnCN;
	private HttpMetadata metadata;
	private String queueId;

	public NewDownloadWindow(HttpMetadata metadata, String fileName, String folderPath) {
		initUI();
		this.metadata = metadata;
		if (this.metadata == null) {
			this.metadata = new HttpMetadata();
		}
		if (this.metadata.getUrl() != null) {
			txtURL.setText(this.metadata.getUrl());
			txtURL.setCaretPosition(0);
		} else {
			try {
				URL url = new URL(XDMUtils.getClipBoardText());
				txtURL.setText(url.toString());
				txtURL.setCaretPosition(0);
			} catch (Exception e) {
				Logger.error(e);
			}
		}
		if (!StringUtils.isNullOrEmptyOrBlank(fileName)) {
			filePane.setFileName(fileName);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(folderPath)) {
			filePane.setFolder(folderPath);
		}

		getRootPane().setDefaultButton(btnDN);

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowActivated(WindowEvent e) {
				txtURL.requestFocus();
			}
		});

		queueId = "";
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if (name.startsWith("QUEUE")) {
				String[] arr = name.split(":");
				if (arr.length < 2) {
					queueId = "";
				} else {
					queueId = arr[1].trim();
				}
				createDownload(false);
			} else if (name.equals("CLOSE")) {
				dispose();
			} else if (name.equals("DOWNLOAD_NOW")) {
				queueId = "";
				createDownload(true);
			} else if (name.equals("BTN_MORE")) {
				if (pop == null) {
					createPopup();
				}
				pop.show(btnMore, 0, btnMore.getHeight());
			} else if (name.equals("IGNORE_URL")) {
				String urlStr = txtURL.getText();
				if (urlStr.length() < 1) {
					return;
				}

				try {
					URL url = new URL(urlStr);
					String host = url.getHost().trim();
					if (StringUtils.isNullOrEmptyOrBlank(host)) {
						return;
					}
					Config.getInstance().addBlockedHosts(host);
					Config.getInstance().save();
					dispose();
					Logger.info("called");

				} catch (Exception e2) {
					Logger.error(e);
				}
			}

		}
	}

	private void createDownload(boolean now) {
		String urlStr = txtURL.getText();
		if (urlStr.length() < 1) {
			JOptionPane.showMessageDialog(this, StringResource.get("MSG_NO_URL"));
			return;
		}
		if (!XDMUtils.validateURL(urlStr)) {
			urlStr = "http://" + urlStr;
			if (!XDMUtils.validateURL(urlStr)) {
				JOptionPane.showMessageDialog(this, StringResource.get("MSG_INVALID_URL"));
				return;
			} else {
				txtURL.setText(urlStr);
			}
		}
		if (!urlStr.equals(metadata.getUrl())) {
			metadata.setUrl(urlStr);
		}
		dispose();
		Logger.info("file: " + filePane.getFileName());
		if (filePane.getFileName().length() < 1) {
			JOptionPane.showMessageDialog(this, StringResource.get("MSG_NO_FILE"));
			return;
		}

		String file = XDMUtils.createSafeFileName(filePane.getFileName());

		XDMApp.getInstance().createDownload(file, filePane.getFolder(), metadata, now, queueId, 0, 0);
	}

	@Override
	public void changedUpdate(DocumentEvent e) {
		update(e);
	}

	@Override
	public void insertUpdate(DocumentEvent e) {
		update(e);
	}

	@Override
	public void removeUpdate(DocumentEvent e) {
		update(e);
	}

	void update(DocumentEvent e) {
		try {
			Document doc = e.getDocument();
			int len = doc.getLength();
			String text = doc.getText(0, len);
			filePane.setFileName(XDMUtils.getFileName(text));
		} catch (Exception err) {
			Logger.error(err);
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
			Logger.error(e);
		}

		setIconImage(ImageResource.getImage("icon.png"));
		setSize(getScaledInt(400), getScaledInt(210));
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(400), getScaledInt(50));

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(365), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");

		closeBtn.setIcon(ImageResource.getIcon("title_close.png", 20, 20));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("ND_TITLE"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(400), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		txtURL = new JTextField();
		txtURL.getDocument().addDocumentListener(this);
		txtURL.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtURL.setBackground(ColorResource.getDarkestBgColor());
		txtURL.setForeground(Color.WHITE);
		txtURL.setBounds(getScaledInt(77), getScaledInt(79), getScaledInt(291), getScaledInt(20));
		txtURL.setCaretColor(ColorResource.getSelectionColor());

		add(txtURL);

		filePane = new XDMFileSelectionPanel();
		filePane.setBounds(getScaledInt(77), getScaledInt(111), getScaledInt(291), getScaledInt(20));
		add(filePane);

		add(titlePanel);

		JLabel lblURL = new JLabel(StringResource.get("ND_ADDRESS"), JLabel.RIGHT);
		lblURL.setFont(FontResource.getNormalFont());
		lblURL.setForeground(Color.WHITE);
		lblURL.setBounds(getScaledInt(10), getScaledInt(78), getScaledInt(61), getScaledInt(23));
		add(lblURL);

		JLabel lblFile = new JLabel(StringResource.get("ND_FILE"), JLabel.RIGHT);
		lblFile.setFont(FontResource.getNormalFont());
		lblFile.setForeground(Color.WHITE);
		lblFile.setBounds(getScaledInt(10), getScaledInt(108), getScaledInt(61), getScaledInt(23));
		add(lblFile);

		JPanel panel = new JPanel(null);
		panel.setBounds(getScaledInt(1), getScaledInt(155), getScaledInt(400), getScaledInt(55));
		panel.setBackground(Color.DARK_GRAY);
		add(panel);

		btnMore = new CustomButton(StringResource.get("ND_MORE"));
		btnDN = new CustomButton(StringResource.get("ND_DOWNLOAD_NOW"));
		btnCN = new CustomButton(StringResource.get("ND_CANCEL"));

		btnMore.setBounds(getScaledInt(0), getScaledInt(1), getScaledInt(120), getScaledInt(55));
		btnMore.setName("BTN_MORE");
		styleButton(btnMore);
		panel.add(btnMore);

		btnDN.setBounds(getScaledInt(121), getScaledInt(1), getScaledInt(160), getScaledInt(55));
		btnDN.setName("DOWNLOAD_NOW");
		styleButton(btnDN);
		panel.add(btnDN);

		btnCN.setBounds(getScaledInt(282), getScaledInt(1), getScaledInt(120), getScaledInt(55));
		btnCN.setName("CLOSE");
		styleButton(btnCN);
		panel.add(btnCN);
	}

	private void createPopup() {
		pop = new JPopupMenu();
		pop.setBackground(ColorResource.getDarkerBgColor());
		JMenu dl = new JMenu(StringResource.get("ND_DOWNLOAD_LATER"));
		dl.setForeground(Color.WHITE);
		dl.setBorder(new EmptyBorder(getScaledInt(5), getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		dl.addActionListener(this);
		dl.setBackground(ColorResource.getDarkerBgColor());
		dl.setBorderPainted(false);
		pop.add(dl);

		createQueueItems(dl);

		JMenuItem ig = new JMenuItem(StringResource.get("ND_IGNORE_URL"));
		ig.setName("IGNORE_URL");
		ig.setForeground(Color.WHITE);
		ig.addActionListener(this);
		pop.add(ig);
		pop.setInvoker(btnMore);
	}

	private void styleButton(CustomButton btn) {
		btn.setBackground(ColorResource.getDarkestBgColor());
		btn.setPressedBackground(ColorResource.getDarkerBgColor());
		btn.setForeground(Color.WHITE);
		btn.setFont(FontResource.getBigFont());
		btn.setBorderPainted(false);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setFocusPainted(false);
		btn.addActionListener(this);
	}

	private void createQueueItems(JMenuItem queueMenuItem) {
		ArrayList<DownloadQueue> queues = XDMApp.getInstance().getQueueList();
		for (DownloadQueue q : queues) {
			JMenuItem mItem = new JMenuItem(q.getName().length() < 1 ? "Default queue" : q.getName());
			mItem.setName("QUEUE:" + q.getQueueId());
			mItem.setForeground(Color.WHITE);
			mItem.addActionListener(this);
			queueMenuItem.add(mItem);
		}
	}

}
