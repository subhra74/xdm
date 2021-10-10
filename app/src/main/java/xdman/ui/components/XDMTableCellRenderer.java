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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.HashMap;
import java.util.Map;

import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;

import xdman.DownloadEntry;
import xdman.XDMConstants;
import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.ui.res.ImageResource;
import xdman.util.FormatUtilities;
import xdman.util.XDMUtils;

public class XDMTableCellRenderer implements TableCellRenderer {

	JLabel iconLbl, titleLbl, statLbl, dateLbl, lineLbl;
	JPanel pcell;
	private Map<String, Icon> iconMap = new HashMap<String, Icon>();

	public XDMTableCellRenderer() {
		titleLbl = new JLabel("This is sample title text");
		titleLbl.setForeground(Color.BLACK);
		iconLbl = new JLabel();
		iconLbl.setForeground(Color.BLACK);
		statLbl = new JLabel("This is sample status text");
		statLbl.setForeground(Color.BLACK);
		dateLbl = new JLabel("Yesterday");
		dateLbl.setForeground(Color.BLACK);
		lineLbl = new JLabel();

		iconLbl.setOpaque(false);
		iconLbl.setPreferredSize(new Dimension(XDMUtils.getScaledInt(56), XDMUtils.getScaledInt(56)));

		iconMap.put("document.png", ImageResource.getIcon("document.png", 48, 48));
		iconMap.put("compressed.png", ImageResource.getIcon("compressed.png", 48, 48));
		iconMap.put("program.png", ImageResource.getIcon("program.png", 48, 48));
		iconMap.put("music.png", ImageResource.getIcon("music.png", 48, 48));
		iconMap.put("video.png", ImageResource.getIcon("video.png", 48, 48));
		iconMap.put("other.png", ImageResource.getIcon("other.png", 48, 48));

		iconLbl.setIcon(iconMap.get("document.png"));

		titleLbl.setBackground(Color.WHITE);
		titleLbl.setFont(FontResource.getItemFont());
		titleLbl.setOpaque(false);

		statLbl.setBackground(Color.WHITE);
		statLbl.setFont(FontResource.getNormalFont());
		statLbl.setOpaque(false);

		dateLbl.setBackground(Color.WHITE);
		dateLbl.setOpaque(false);
		dateLbl.setFont(FontResource.getNormalFont());

		lineLbl = new JLabel();
		lineLbl.setBackground(ColorResource.getWhite());
		lineLbl.setOpaque(true);
		lineLbl.setMinimumSize(new Dimension(10, 1));
		lineLbl.setMaximumSize(new Dimension(lineLbl.getMaximumSize().width, 1));
		lineLbl.setPreferredSize(new Dimension(lineLbl.getPreferredSize().width, 1));

		pcell = new JPanel(new BorderLayout());
		pcell.setBackground(Color.WHITE);

		pcell.add(iconLbl, BorderLayout.WEST);

		Box box = Box.createHorizontalBox();
		box.add(statLbl);
		box.add(Box.createHorizontalGlue());
		box.add(dateLbl);
		box.setBorder(new EmptyBorder(0, 0, XDMUtils.getScaledInt(10), 0));

		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(titleLbl);
		p.add(box, BorderLayout.SOUTH);
		p.setBorder(new EmptyBorder(XDMUtils.getScaledInt(5), 0, XDMUtils.getScaledInt(5), XDMUtils.getScaledInt(5)));

		pcell.add(p);
		pcell.add(lineLbl, BorderLayout.SOUTH);
		pcell.setBorder(new EmptyBorder(0, XDMUtils.getScaledInt(15), 0, XDMUtils.getScaledInt(15)));
	}

	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
			int row, int column) {
		DownloadEntry ent = (DownloadEntry) value;
		titleLbl.setText(ent.getFile());
		dateLbl.setText(ent.getDateStr());
		statLbl.setText(FormatUtilities.getFormattedStatus(ent));
		if (isSelected) {
			pcell.setBackground(ColorResource.getSelectionColor());
			lineLbl.setOpaque(false);
			titleLbl.setForeground(Color.WHITE);
			dateLbl.setForeground(Color.WHITE);
			statLbl.setForeground(Color.WHITE);
		} else {
			pcell.setBackground(Color.WHITE);
			lineLbl.setOpaque(true);
			titleLbl.setForeground(Color.BLACK);
			dateLbl.setForeground(Color.BLACK);
			statLbl.setForeground(Color.BLACK);
		}
		switch (ent.getCategory()) {
		case XDMConstants.DOCUMENTS:
			iconLbl.setIcon(iconMap.get("document.png"));
			break;
		case XDMConstants.COMPRESSED:
			iconLbl.setIcon(iconMap.get("compressed.png"));
			break;
		case XDMConstants.PROGRAMS:
			iconLbl.setIcon(iconMap.get("program.png"));
			break;
		case XDMConstants.MUSIC:
			iconLbl.setIcon(iconMap.get("music.png"));
			break;
		case XDMConstants.VIDEO:
			iconLbl.setIcon(iconMap.get("video.png"));
			break;
		default:
			iconLbl.setIcon(iconMap.get("other.png"));
			break;
		}
		return pcell;
	}
}
