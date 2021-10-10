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
import java.util.EventObject;

import javax.swing.AbstractCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellEditor;

import org.tinylog.Logger;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;

public class VideoItemEditor extends AbstractCellEditor implements TableCellEditor {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8968170364342941003L;
	private JPanel panel;
	private JLabel lbl;
	private JComboBox<String> cmb;
	private DefaultComboBoxModel<String> cmbModel;
	private VideoItemWrapper obj;
	private JLabel lblIcon;
	private JPanel component;
	private JLabel lblBorder;
	private JCheckBox chk;
	private Icon ico;
	private MediaImageSource imgSource;

	public VideoItemEditor(MediaImageSource imgSource) {
		component = new JPanel(new BorderLayout(getScaledInt(5), getScaledInt(5)));
		component.setBorder(new EmptyBorder(0, getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		panel = new JPanel(new BorderLayout());
		lbl = new JLabel();
		lbl.setVerticalAlignment(JLabel.CENTER);
		lbl.setVerticalTextPosition(JLabel.CENTER);
		lbl.setFont(FontResource.getBigFont());
		lblIcon = new JLabel();
		lblIcon.setOpaque(true);
		lblIcon.setMinimumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setMaximumSize(new Dimension(getScaledInt(119), getScaledInt(92)));
		lblIcon.setPreferredSize(new Dimension(getScaledInt(119), getScaledInt(92)));
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
		cmbModel = new DefaultComboBoxModel<>();
		cmb = new JComboBox<>(cmbModel);
		cmb.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(30)));
		cmb.setOpaque(false);
		cmb.setBorder(null);
		panel.add(lbl);
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

	public void select(boolean flag) {
		chk.setSelected(flag);
	}

	@Override
	public Object getCellEditorValue() {
		obj.videoItem.index = cmb.getSelectedIndex();
		obj.checked = chk.isSelected();
		Logger.info("value " + obj.videoItem.title + " " + obj.videoItem.index);
		return obj;
	}

	@Override
	public boolean shouldSelectCell(EventObject anEvent) {
		return true;
	}

	@Override
	public boolean isCellEditable(EventObject e) {
		return true;
	}

	@Override
	public boolean stopCellEditing() {
		Logger.info("requesting stop");
		fireEditingStopped();
		return true;
	}

	@Override
	public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
		this.obj = (VideoItemWrapper) value;
		lbl.setText(obj.videoItem.title);
		cmbModel.removeAllElements();
		for (int i = 0; i < obj.videoItem.mediaFormats.size(); i++)
			cmbModel.addElement(obj.videoItem.mediaFormats.get(i) + "");
		cmb.setSelectedIndex(obj.videoItem.index);
		if (row == 0) {
			lblBorder.setOpaque(false);
		} else {
			lblBorder.setOpaque(true);
		}
		chk.setSelected(obj.checked);

		lblIcon.setIcon(ico);
		if (obj.videoItem.thumbnail != null) {
			if (imgSource != null) {
				ImageIcon icon = imgSource.getImage(obj.videoItem.thumbnail);
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