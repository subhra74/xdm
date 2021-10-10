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

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import xdman.mediaconversion.ConversionItem;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.ui.res.StringResource;
import xdman.util.StringUtils;

@SuppressWarnings("FieldCanBeLocal")
public class ConversionItemRender implements ListCellRenderer<ConversionItem> {

	private final JPanel panel;
	private final JPanel component;
	private final JLabel lbl;
	private final JLabel lblIcon;
	private final JLabel lblVideoDet;
	private final JLabel lblBorder;
	private final Icon ico;

	public ConversionItemRender() {
		component = new JPanel(new BorderLayout(getScaledInt(5), getScaledInt(5)));
		component.setBackground(ColorResource.getDarkestBgColor());
		component.setBorder(new EmptyBorder(0, getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		panel = new JPanel(new BorderLayout());
		lblIcon = new JLabel();
		lblIcon.setOpaque(true);
		lblIcon.setPreferredSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setMinimumSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setMaximumSize(new Dimension(getScaledInt(64), getScaledInt(64)));
		lblIcon.setHorizontalAlignment(JLabel.CENTER);

		ico = ImageResource.getIcon("video.png", 48, 48);
		lblIcon.setIcon(ico);
		lblIcon.setVerticalAlignment(JLabel.CENTER);

		JPanel p1 = new JPanel(new BorderLayout());
		p1.setOpaque(false);
		p1.add(lblIcon);
		p1.setBorder(new EmptyBorder(getScaledInt(12), getScaledInt(5), getScaledInt(5), getScaledInt(5)));
		component.add(p1, BorderLayout.WEST);
		lbl = new JLabel();
		lbl.setFont(FontResource.getItemFont());
		lbl.setBorder(new EmptyBorder(0, 0, 0, getScaledInt(5)));
		lbl.setVerticalAlignment(JLabel.CENTER);
		panel.add(lbl);
		lblVideoDet = new JLabel();
		lblVideoDet.setPreferredSize(new Dimension(getScaledInt(200), getScaledInt(30)));
		lblVideoDet.setOpaque(false);
		lblVideoDet.setVerticalAlignment(JLabel.TOP);

		panel.add(lblVideoDet, BorderLayout.SOUTH);
		panel.setOpaque(false);
		panel.setBorder(new EmptyBorder(getScaledInt(5), 0, getScaledInt(7), getScaledInt(5)));

		component.add(panel);
		lblBorder = new JLabel();
		lblBorder.setPreferredSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setMaximumSize(new Dimension(getScaledInt(100), 1));
		lblBorder.setBackground(ColorResource.getDarkestBgColor());
		component.add(lblBorder, BorderLayout.NORTH);
		component.setOpaque(true);
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends ConversionItem> list, ConversionItem value, int index,
			boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			component.setBackground(ColorResource.getSelectionColor());
		} else {
			component.setBackground(ColorResource.getDarkestBgColor());
		}
		lbl.setText(value.inputFileName);
		StringBuilder buf = new StringBuilder();
		if (value.info != null) {
			if (value.info.thumbnail != null) {
				lblIcon.setIcon(value.info.thumbnail);
			}
			if (!StringUtils.isNullOrEmptyOrBlank(value.info.duration)) {
				buf.append("[").append(value.info.duration).append("]");
			}
			if (!StringUtils.isNullOrEmptyOrBlank(value.info.resolution)) {
				buf.append(buf.length() > 0 ? " " : "");
				buf.append(value.info.resolution);
			}
			if (value.conversionState == 1) {
				if (buf.length() > 0) {
					buf.append(" - ");
				}
				buf.append(StringResource.get("LBL_CONV_SUCCESS"));
			} else if (value.conversionState == 2) {
				if (buf.length() > 0) {
					buf.append(" - ");
				}
				buf.append(StringResource.get("LBL_CONV_FAILED"));
			}
			if (buf.length() > 0) {
				lblVideoDet.setText(buf.toString());
			}
		}
		lbl.setText(value.inputFileName);
		lblBorder.setOpaque(index != 0);
		return component;
	}

}
