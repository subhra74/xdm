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
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;

import org.tinylog.Logger;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.videoparser.YdlResponse.YdlVideo;

@SuppressWarnings("FieldCanBeLocal")
public class VideoItemRenderer implements TableCellRenderer {
	private final JPanel panel;
	private final JPanel component;
	private final JLabel lbl;
	private final JLabel lblIcon;
	private final JComboBox<String> cmb;
	private final DefaultComboBoxModel<String> cmbModel;
	private final JLabel lblBorder;
	private final JCheckBox chk;
	private final MediaImageSource imgSource;
	private final Icon ico;

	public VideoItemRenderer(MediaImageSource imgSource) {
		component = new JPanel(new BorderLayout(getScaledInt(5), getScaledInt(5)));
		component.setBorder(new EmptyBorder(0, getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		panel = new JPanel(new BorderLayout());
		lblIcon = new JLabel();
		lblIcon.setOpaque(true);
		lblIcon.setPreferredSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setMinimumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setMaximumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setHorizontalAlignment(JLabel.CENTER);

		ico = ImageResource.getIcon("videoplay.png", 94, 92);
		lblIcon.setIcon(ico);
		lblIcon.setVerticalAlignment(JLabel.CENTER);

		JPanel p1 = new JPanel(new BorderLayout());
		p1.setOpaque(false);
		p1.add(lblIcon);
		chk = new JCheckBox("");
		chk.setOpaque(false);
		chk.setIcon(ImageResource.getIcon("unchecked.png", 16, 16));
		chk.setSelectedIcon(ImageResource.getIcon("checked.png", 16, 16));
		p1.add(chk, BorderLayout.WEST);
		p1.setBorder(new EmptyBorder(getScaledInt(12), 0, getScaledInt(5), getScaledInt(5)));
		component.add(p1, BorderLayout.WEST);
		lbl = new JLabel();
		lbl.setVerticalAlignment(JLabel.CENTER);
		lbl.setVerticalTextPosition(JLabel.CENTER);
		lbl.setFont(FontResource.getBigFont());
		panel.add(lbl);
		cmbModel = new DefaultComboBoxModel<>();
		cmb = new JComboBox<>(cmbModel);
		cmb.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(30)));

		cmb.setOpaque(false);
		cmb.setBorder(null);
		panel.add(cmb, BorderLayout.SOUTH);
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(0, 0, getScaledInt(5), getScaledInt(5)));
		component.add(panel);
		lblBorder = new JLabel();
		lblBorder.setPreferredSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setMaximumSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setBackground(ColorResource.getDarkerBgColor());
		component.add(lblBorder, BorderLayout.NORTH);
		component.setOpaque(false);
		this.imgSource = imgSource;
	}

	@Override
	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		VideoItemWrapper wrapper = (VideoItemWrapper) value;
		YdlVideo obj = wrapper.videoItem;
		lbl.setText(obj.title);
		cmbModel.removeAllElements();
		cmbModel.addElement(obj.mediaFormats.get(obj.index) + "");
		lblBorder.setOpaque(row != 0);
		lblIcon.setIcon(ico);
		chk.setSelected(wrapper.checked);
		if (obj.thumbnail != null) {
			if (imgSource != null) {
				ImageIcon icon = imgSource.getImage(obj.thumbnail);
				if (icon != null) {
					lblIcon.setIcon(icon);
				} else {
					Logger.info("null");
				}
			}
		}

		return component;
	}
}