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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.border.LineBorder;

import org.tinylog.Logger;

import xdman.Config;
import xdman.ui.res.ColorResource;
import xdman.ui.res.ImageResource;
import xdman.util.StringUtils;

public class XDMFileSelectionPanel extends JPanel implements ActionListener {
	private static final long serialVersionUID = 2333430406492555559L;
	private JTextField txtFile;
	private JButton btnBrowse;
	private JButton btnDropdown;
	private JPopupMenu pop;

	public XDMFileSelectionPanel() {
		super(new BorderLayout());
		initUI();
	}

	String folder;

	public String getFileName() {
		return txtFile.getText();
	}

	public void setFileName(String file) {
		txtFile.setText(file);
		txtFile.setCaretPosition(0);
	}

	public String getFolder() {
		return folder;
	}

	public void setFolder(String f) {
		this.folder = f;
	}

	private void initUI() {
		setBackground(ColorResource.getDarkestBgColor());
		txtFile = new JTextField();
		setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtFile.setBackground(ColorResource.getDarkestBgColor());
		txtFile.setBorder(null);
		txtFile.setForeground(Color.WHITE);
		txtFile.setBounds(getScaledInt(77), getScaledInt(111), getScaledInt(241), getScaledInt(20));
		txtFile.setCaretColor(ColorResource.getSelectionColor());

		add(txtFile);
		Box hbox = Box.createHorizontalBox();

		btnBrowse = new CustomButton();
		btnBrowse.setBackground(ColorResource.getDarkestBgColor());
		btnBrowse.setIcon(ImageResource.getIcon("folder.png", 16, 16));
		btnBrowse.setMargin(new Insets(0, 0, 0, 0));
		btnBrowse.setContentAreaFilled(false);
		btnBrowse.setBorderPainted(false);
		btnBrowse.setFocusPainted(false);
		btnBrowse.setOpaque(false);
		btnBrowse.addActionListener(this);

		btnDropdown = new CustomButton();
		btnDropdown.setBackground(ColorResource.getDarkestBgColor());
		btnDropdown.setIcon(ImageResource.getIcon("down_white.png", 16, 16));
		btnDropdown.setMargin(new Insets(0, 0, 0, 0));
		btnDropdown.setContentAreaFilled(false);
		btnDropdown.setBorderPainted(false);
		btnDropdown.setFocusPainted(false);
		btnDropdown.addActionListener(this);

		hbox.add(btnBrowse);
		hbox.add(btnDropdown);

		add(hbox, BorderLayout.EAST);
		pop = new JPopupMenu();
		if (!StringUtils.isNullOrEmptyOrBlank(Config.getInstance().getLastFolder())) {
			pop.add(createMenuItem(Config.getInstance().getLastFolder()));
		}
		pop.add(createMenuItem(Config.getInstance().getDownloadFolder()));
		if (!Config.getInstance().isForceSingleFolder()) {
			pop.add(createMenuItem(Config.getInstance().getCategoryDocuments()));
			pop.add(createMenuItem(Config.getInstance().getCategoryMusic()));
			pop.add(createMenuItem(Config.getInstance().getCategoryPrograms()));
			pop.add(createMenuItem(Config.getInstance().getCategoryCompressed()));
			pop.add(createMenuItem(Config.getInstance().getCategoryVideos()));
		}
		pop.setInvoker(btnDropdown);
	}

	private JMenuItem createMenuItem(String folder) {
		JMenuItem item = new JMenuItem(folder);
		item.addActionListener(this);
		return item;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JMenuItem) {
			this.folder = ((JMenuItem) e.getSource()).getText();
			Logger.info("Selected folder: " + this.folder);
		}
		if (e.getSource() == btnBrowse) {
			choseFolder();
		}
		if (e.getSource() == btnDropdown) {
			pop.show(txtFile, 0, txtFile.getHeight());
		}
	}

	private void choseFolder() {
		String dir = folder;
		if (StringUtils.isNullOrEmptyOrBlank(dir)) {
			dir = Config.getInstance().getLastFolder();
			if (StringUtils.isNullOrEmptyOrBlank(dir)) {
				dir = Config.getInstance().getDownloadFolder();
			}
		}
		JFileChooser jfc = XDMFileChooser.getFileChooser(JFileChooser.DIRECTORIES_ONLY, new File(dir));
		if (jfc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			folder = jfc.getSelectedFile().getAbsolutePath();
			Config.getInstance().setLastFolder(folder);
		}
	}

	public void setFocus() {
		txtFile.requestFocus();
	}
}
