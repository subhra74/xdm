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

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;

import xdman.DownloadEntry;
import xdman.XDMApp;
import xdman.util.XDMUtils;

public class DownloadListView {

	private final DownloadTableModel model;
	private final JTable table;

	public DownloadListView(JPanel container) {
		model = new DownloadTableModel();
		XDMApp.getInstance().addListener(model);
		table = new JTable(model);
		table.setTableHeader(null);
		table.setDefaultRenderer(DownloadEntry.class, new XDMTableCellRenderer());
		table.setRowHeight(XDMUtils.getScaledInt(70));
		table.setShowGrid(false);
		table.setFillsViewportHeight(true);
		table.setBorder(new EmptyBorder(0, 0, 0, 0));
		table.setDragEnabled(true);

		JScrollPane jsp = new JScrollPane(table);
		jsp.setBorder(new EmptyBorder(0, 0, 0, 0));

		container.add(jsp);
	}

	public JTable getTable() {
		return table;
	}

	public String[] getSelectedIds() {
		String[] arr = new String[table.getSelectedRowCount()];

		int[] selectedRows = table.getSelectedRows();
		for (int i = 0; i < selectedRows.length; i++) {
			arr[i] = model.getIdAt(selectedRows[i]);
		}
		return arr;
	}

	public void refresh() {
		model.listChanged();
	}

}
