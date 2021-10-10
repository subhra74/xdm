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

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.LineBorder;

import org.tinylog.Logger;

import xdman.Config;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.XDMUtils;

public class DownloadCompleteWnd extends JDialog implements ActionListener {

	private static final long serialVersionUID = -6513935849910094705L;
	JTextField txtFile, txtFolder;
	JCheckBox chkDontShow;

	public DownloadCompleteWnd(String file, String folder) {
		initUI(file, folder);
	}

	private void initUI(String file, String folder) {
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

		setSize(getScaledInt(350), getScaledInt(210));
		setLocationRelativeTo(null);
		setAlwaysOnTop(true);
		getContentPane().setLayout(null);
		getContentPane().setBackground(ColorResource.getDarkestBgColor());

		JPanel titlePanel = new TitlePanel(null, this);
		titlePanel.setOpaque(false);
		titlePanel.setBounds(0, 0, getScaledInt(350), getScaledInt(50));
		add(titlePanel);

		JButton closeBtn = new CustomButton();
		closeBtn.setBounds(getScaledInt(310), getScaledInt(5), getScaledInt(30), getScaledInt(30));
		closeBtn.setBackground(ColorResource.getDarkestBgColor());
		closeBtn.setBorderPainted(false);
		closeBtn.setFocusPainted(false);
		closeBtn.setName("CLOSE");
		closeBtn.setIcon(ImageResource.getIcon("title_close.png", 20, 20));
		closeBtn.addActionListener(this);
		titlePanel.add(closeBtn);

		JLabel titleLbl = new JLabel(StringResource.get("CD_TITLE"));
		titleLbl.setFont(FontResource.getBiggerFont());
		titleLbl.setForeground(ColorResource.getSelectionColor());
		titleLbl.setBounds(getScaledInt(25), getScaledInt(15), getScaledInt(200), getScaledInt(30));
		titlePanel.add(titleLbl);

		JLabel lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getSelectionColor());
		lineLbl.setBounds(0, getScaledInt(55), getScaledInt(350), 1);
		lineLbl.setOpaque(true);
		add(lineLbl);

		JLabel lblFile = new JLabel(StringResource.get("ND_FILE"), JLabel.RIGHT);
		lblFile.setBounds(0, getScaledInt(75), getScaledInt(70), getScaledInt(20));
		lblFile.setForeground(Color.WHITE);
		add(lblFile);

		JLabel lblSave = new JLabel(StringResource.get("CD_LOC"), JLabel.RIGHT);
		lblSave.setBounds(0, getScaledInt(100), getScaledInt(70), getScaledInt(20));
		lblSave.setForeground(Color.WHITE);
		add(lblSave);

		txtFile = new JTextField();
		txtFile.setText(file);
		txtFile.setEditable(false);
		txtFile.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtFile.setBackground(ColorResource.getDarkestBgColor());
		txtFile.setForeground(Color.WHITE);
		txtFile.setBounds(getScaledInt(80), getScaledInt(75), getScaledInt(220), getScaledInt(20));
		txtFile.setCaretColor(ColorResource.getSelectionColor());
		add(txtFile);

		txtFolder = new JTextField();
		txtFolder.setText(folder);
		txtFolder.setEditable(false);
		txtFolder.setBorder(new LineBorder(ColorResource.getSelectionColor(), 1));
		txtFolder.setBackground(ColorResource.getDarkestBgColor());
		txtFolder.setForeground(Color.WHITE);
		txtFolder.setBounds(getScaledInt(80), getScaledInt(100), getScaledInt(220), getScaledInt(20));
		txtFolder.setCaretColor(ColorResource.getSelectionColor());
		add(txtFolder);

		chkDontShow = new JCheckBox(StringResource.get("MSG_DONT_SHOW_AGAIN"));
		chkDontShow.setBackground(ColorResource.getDarkestBgColor());
		chkDontShow.setName("MSG_DONT_SHOW_AGAIN");
		chkDontShow.setForeground(Color.WHITE);
		chkDontShow.setFocusPainted(false);

		chkDontShow.setBounds(getScaledInt(75), getScaledInt(125), getScaledInt(200), getScaledInt(20));
		chkDontShow.setIcon(ImageResource.getIcon("unchecked.png", 16, 16));
		chkDontShow.setSelectedIcon(ImageResource.getIcon("checked.png", 16, 16));
		chkDontShow.addActionListener(this);

		add(chkDontShow);

		JPanel panel = new JPanel(null);
		panel.setBounds(0, getScaledInt(155), getScaledInt(400), getScaledInt(55));
		panel.setBackground(Color.DARK_GRAY);
		add(panel);

		CustomButton btnMore = new CustomButton(StringResource.get("CD_OPEN_FILE")),
				btnDN = new CustomButton(StringResource.get("CD_OPEN_FOLDER")),
				btnCN = new CustomButton(StringResource.get("ND_CANCEL"));

		btnMore.setBounds(0, 1, getScaledInt(100), getScaledInt(55));
		btnMore.setName("CTX_OPEN_FILE");
		styleButton(btnMore);
		panel.add(btnMore);

		btnDN.setBounds(getScaledInt(101), 1, getScaledInt(148), getScaledInt(55));
		btnDN.setName("CTX_OPEN_FOLDER");
		styleButton(btnDN);
		panel.add(btnDN);

		btnCN.setBounds(getScaledInt(250), 1, getScaledInt(100), getScaledInt(55));
		btnCN.setName("CLOSE");
		styleButton(btnCN);
		panel.add(btnCN);

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

	@Override
	public void actionPerformed(ActionEvent e) {
		if (e.getSource() instanceof JComponent) {
			String name = ((JComponent) e.getSource()).getName();
			if (name.startsWith("MSG_DONT_SHOW_AGAIN")) {
				Config.getInstance().setShowDownloadCompleteWindow(!chkDontShow.isSelected());
			} else if (name.equals("CLOSE")) {
				dispose();
			} else if (name.equals("CTX_OPEN_FILE")) {
				try {
					XDMUtils.openFile(txtFile.getText(), txtFolder.getText());
					dispose();
				} catch (Exception e1) {
					Logger.error(e1);
				}
			} else if (name.equals("CTX_OPEN_FOLDER")) {
				try {
					XDMUtils.openFolder(txtFile.getText(), txtFolder.getText());
					dispose();
				} catch (Exception e1) {
					Logger.error(e1);
				}
			}
		}
	}

}
