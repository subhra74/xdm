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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URL;
import java.util.*;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.border.LineBorder;

import org.tinylog.Logger;

import xdman.Config;
import xdman.DownloadQueue;
import xdman.QueueManager;
import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class BatchDownloadWnd extends JFrame implements ActionListener {

	private static final long serialVersionUID = -8209791156319603983L;
	JList<BatchItem> list;
	DefaultListModel<BatchItem> model;
	JTextField txtFile;
	DefaultComboBoxModel<DownloadQueue> queueModel;
	JComboBox<DownloadQueue> cmbQueues;
	JCheckBox chkStartQueue;
	Set<String> fileExts;
	DefaultComboBoxModel<String> filterModel;
	JComboBox<String> cmbFilter;
	BatchItem[] items;

	public static List<String> getUrls() {
		List<String> urls = new ArrayList<>();
		String text = XDMUtils.getClipBoardText();
		if (!StringUtils.isNullOrEmptyOrBlank(text)) {
			Logger.info(text);
			String[] arr = text.split("\n");
			for (String url : arr) {
				try {
					new URL(url);
					urls.add(url);
				} catch (Exception e) {
					Logger.error(e);
				}
			}
		}
		return urls;
	}

	public BatchDownloadWnd(List<HttpMetadata> mdList) {
		fileExts = new HashSet<>();
		items = new BatchItem[mdList.size()];
		initUI();
		for (int i = 0; i < mdList.size(); i++) {
			HttpMetadata md = mdList.get(i);
			try {
				String file = XDMUtils.getFileName(md.getUrl());
				BatchItem item = new BatchItem();
				item.file = file;
				item.selected = true;
				item.metadata = md;
				items[i] = item;
				model.addElement(item);
				String ext = XDMUtils.getExtension(file);
				if (!StringUtils.isNullOrEmptyOrBlank(ext)) {
					fileExts.add(ext);
					Logger.info("adding ext: " + ext);
				}
			} catch (Exception e) {
				Logger.error(e);
			}
		}

		for (String ext : fileExts) {
			filterModel.addElement(ext);
		}
		filterModel.insertElementAt("All files", 0);
		cmbFilter.setSelectedIndex(0);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if (name.startsWith("CLOSE")) {
				dispose();
			} else if (name.startsWith("BROWSE_FOLDER")) {
				JFileChooser jfc = XDMFileChooser.getFileChooser(JFileChooser.DIRECTORIES_ONLY,
						new File(Config.getInstance().getDownloadFolder()));
				if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
					txtFile.setText(jfc.getSelectedFile().getAbsolutePath());
				}
			} else if (name.equals("DOWNLOAD")) {
				DownloadQueue queue = (DownloadQueue) cmbQueues.getSelectedItem();
				if (queue != null) {
					createDownload(queue);
					if (chkStartQueue.isSelected()) {
						queue.start();
					}
				}
				dispose();
			}
		}

	}

	private void createDownload(DownloadQueue q) {
		txtFile.getText();
		String folder;
		for (int i = 0; i < model.size(); i++) {
			BatchItem item = model.getElementAt(i);
			if (item.selected) {
				String file = item.file;
				HttpMetadata metadata = item.metadata;
				folder = txtFile.getText();
				XDMApp.getInstance().createDownload(file, folder, metadata, false, q == null ? "" : q.getQueueId(), 0,
						0);
			}
		}
	}

	private void initUI() {

		model = new DefaultListModel<>();
		list = new JList<>(model);

		setUndecorated(true);

		try {
			if (GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
					.isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT)) {
				if (!Config.getInstance().isNoTransparency())
					setOpacity(0.85f);
			}
		} catch (Exception e) {
			Logger.error(e);
		}

		setTitle(StringResource.get("MENU_BATCH_DOWNLOAD"));
		setIconImage(ImageResource.getImage("icon.png"));
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

		closeBtn.setIcon(ImageResource.getIcon("title_close.png", 20, 20));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("MENU_BATCH_DOWNLOAD"));
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
		int h = getScaledInt(420) - getScaledInt(100) - getScaledInt(70) - getScaledInt(20);

		list.setBorder(null);
		list.setOpaque(false);
		list.setCellRenderer(new SimpleCheckboxRender());

		JScrollPane jsp = new JScrollPane(list);
		jsp.setBounds(0, y, getWidth(), h);
		jsp.getViewport().setOpaque(false);
		jsp.setBorder(null);
		jsp.setOpaque(false);
		DarkScrollBar scrollBar = new DarkScrollBar(JScrollBar.VERTICAL);
		jsp.setVerticalScrollBar(scrollBar);
		jsp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		add(jsp);
		y += h;

		JLabel lineLbl2 = new JLabel();
		lineLbl2.setBackground(ColorResource.getDarkBgColor());
		lineLbl2.setBounds(0, y, getWidth(), 1);
		lineLbl2.setOpaque(true);
		add(lineLbl2);

		y += getScaledInt(5);

		JLabel lblFileTypes = new JLabel(StringResource.get("LBL_FILE_TYPE"), JLabel.RIGHT);
		lblFileTypes.setFont(FontResource.getNormalFont());
		lblFileTypes.setForeground(Color.WHITE);
		lblFileTypes.setBounds(0, y, getScaledInt(80), getScaledInt(30));
		add(lblFileTypes);

		filterModel = new DefaultComboBoxModel<>();

		cmbFilter = new JComboBox<>(filterModel);
		cmbFilter.setRenderer(new SimpleListRenderer());
		cmbFilter.setBounds(getScaledInt(90), y + getScaledInt(5),
				getScaledInt(305) - getScaledInt(15) + getScaledInt(50), getScaledInt(20));
		add(cmbFilter);
		cmbFilter.addActionListener(e -> {

			model.removeAllElements();
			for (BatchItem item : items) {
				boolean add = true;
				if (cmbFilter.getSelectedIndex() > 0) {
					String ext = Objects.requireNonNull(cmbFilter.getSelectedItem()).toString();
					add = item.file.endsWith(ext);
				}
				if (add) {
					model.addElement(item);
				}
			}
		});

		y += getScaledInt(25);

		JLabel lblFile = new JLabel(StringResource.get("LBL_SAVE_IN"), JLabel.RIGHT);
		lblFile.setFont(FontResource.getNormalFont());
		lblFile.setForeground(Color.WHITE);
		lblFile.setBounds(0, y, getScaledInt(80), getScaledInt(30));
		add(lblFile);

		txtFile = new JTextField();
		txtFile.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtFile.setBackground(ColorResource.getDarkestBgColor());
		txtFile.setForeground(Color.WHITE);
		txtFile.setBounds(getScaledInt(90), y + getScaledInt(5), getScaledInt(305) - getScaledInt(15),
				getScaledInt(20));
		txtFile.setCaretColor(ColorResource.getSelectionColor());
		txtFile.setText(Config.getInstance().getDownloadFolder());
		add(txtFile);

		JButton browse = new CustomButton("...");
		browse.setName("BROWSE_FOLDER");
		browse.setMargin(new Insets(0, 0, 0, 0));
		browse.setBounds(getScaledInt(410) - getScaledInt(20), y + getScaledInt(5), getScaledInt(40), getScaledInt(20));
		browse.setFocusPainted(false);
		browse.setBackground(ColorResource.getDarkestBgColor());
		browse.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		browse.setForeground(Color.WHITE);
		browse.addActionListener(this);
		browse.setFont(FontResource.getItemFont());
		add(browse);

		y += getScaledInt(30);

		JLabel lblQueue = new JLabel(StringResource.get("LBL_QUEUE_USE"), JLabel.RIGHT);
		lblQueue.setFont(FontResource.getNormalFont());
		lblQueue.setForeground(Color.WHITE);
		lblQueue.setBounds(0, y, getScaledInt(80), getScaledInt(30));
		add(lblQueue);

		queueModel = new DefaultComboBoxModel<>();
		ArrayList<DownloadQueue> qlist = QueueManager.getInstance().getQueueList();
		for (DownloadQueue downloadQueue : qlist) {
			queueModel.addElement(downloadQueue);
		}
		cmbQueues = new JComboBox<>(queueModel);
		cmbQueues.setRenderer(new QueueListRenderer());
		cmbQueues.setBounds(getScaledInt(90), y, getScaledInt(305) - getScaledInt(15) + getScaledInt(50),
				getScaledInt(20));
		add(cmbQueues);

		y += getScaledInt(35);

		chkStartQueue = new JCheckBox(StringResource.get("LBL_START_QUEUE_PROCESSING"));
		chkStartQueue.setBackground(ColorResource.getDarkestBgColor());
		chkStartQueue.setName("START_QUEUE");
		chkStartQueue.setForeground(Color.WHITE);
		chkStartQueue.setFocusPainted(false);

		chkStartQueue.setBounds(getScaledInt(15), y, getScaledInt(200), getScaledInt(20));
		chkStartQueue.setIcon(ImageResource.getIcon("unchecked.png", 16, 16));
		chkStartQueue.setSelectedIcon(ImageResource.getIcon("checked.png", 16, 16));
		chkStartQueue.addActionListener(this);

		add(chkStartQueue);

		h = getScaledInt(25);
		JButton btnDwn = createButton("MB_OK");
		btnDwn.setBounds(getWidth() - getScaledInt(15) - getScaledInt(100), y, getScaledInt(100), h);
		btnDwn.setName("DOWNLOAD");
		add(btnDwn);

		JButton btnCn = createButton("ND_CANCEL");
		btnCn.setBounds(getWidth() - getScaledInt(15) - getScaledInt(100) - getScaledInt(110), y, getScaledInt(100), h);
		btnCn.setName("CLOSE");
		add(btnCn);

		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				int index = list.locationToIndex(event.getPoint());// Get index of item
				BatchItem cItem = model.getElementAt(index);
				cItem.selected = !cItem.selected; // Toggle selected state
				list.repaint(list.getCellBounds(index, index));// Repaint cell
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

}

class BatchItem {

	String file;
	boolean selected;
	HttpMetadata metadata;

	@Override
	public String toString() {
		return file;
	}

}