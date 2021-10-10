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

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.border.EmptyBorder;

import xdman.mediaconversion.Format;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.util.StringUtils;

public class MediaFormatRender implements ListCellRenderer<Format> {

	private final JPanel component;
	private final JLabel lbl;
	private final JLabel lblVideoDet;

	public MediaFormatRender() {
		component = new JPanel(new BorderLayout());
		component.setBackground(ColorResource.getDarkerBgColor());
		component.setBorder(new EmptyBorder(getScaledInt(10), getScaledInt(10), getScaledInt(10), getScaledInt(10)));

		lbl = new JLabel();
		lbl.setFont(FontResource.getBigFont());
		component.add(lbl);

		lblVideoDet = new JLabel();
		lblVideoDet.setOpaque(false);

		component.add(lblVideoDet, BorderLayout.SOUTH);
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends Format> list, Format value, int index,
			boolean isSelected, boolean cellHasFocus) {
		if (isSelected) {
			component.setBackground(ColorResource.getSelectionColor());
		} else {
			component.setBackground(ColorResource.getDarkerBgColor());
		}
		lbl.setText(value.getDesc().trim());
		StringBuilder buf = new StringBuilder();
		String videoCodec = value.getDefautValue(value.getVideoCodecs(), value.getDefautVideoCodec());
		String audioCodec = value.getDefautValue(value.getAudioCodecs(), value.getDefautAudioCodec());
		String resolution = value.getDefautValue(value.getResolutions(), value.getDefaultResolution());

		if (!StringUtils.isNullOrEmptyOrBlank(resolution)) {
			buf.append(resolution);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(videoCodec)) {
			buf.append(buf.length() > 0 ? " / " : "");
			buf.append(videoCodec);
		}
		if (!StringUtils.isNullOrEmptyOrBlank(audioCodec)) {
			buf.append(buf.length() > 0 ? " - " : "");
			buf.append(audioCodec);
		}
		if (buf.length() > 0) {
			lblVideoDet.setText(buf.toString());
		}
		return component;
	}

}
