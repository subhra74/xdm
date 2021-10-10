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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.swing.table.AbstractTableModel;

import org.tinylog.Logger;

import xdman.Config;
import xdman.DownloadEntry;
import xdman.ListChangeListener;
import xdman.XDMApp;

@SuppressWarnings("unused")
public class DownloadTableModel extends AbstractTableModel implements ListChangeListener {

	private static final long serialVersionUID = 5474784018135644748L;
	ArrayList<String> idList;
	Map<String, Integer> idIndexMap;
	DownloadSorter _sorter;

	public DownloadTableModel() {
		idList = new ArrayList<>();
		idIndexMap = new HashMap<>();
	}

	@Override
	public int getColumnCount() {
		return 1;
	}

	@Override
	public int getRowCount() {
		return idList.size();
	}

	@Override
	public Class<?> getColumnClass(int c) {
		return DownloadEntry.class;
	}

	@Override
	public Object getValueAt(int row, int col) {
		String id = idList.get(row);
		if (id == null)
			return null;
		return XDMApp.getInstance().getEntry(id);
	}

	@Override
	public void listChanged() {
		Logger.info("List changed");
		idList = XDMApp.getInstance().getDownloadList(Config.getInstance().getCategoryFilter(),
				Config.getInstance().getStateFilter(), Config.getInstance().getSearchText(),
				Config.getInstance().getQueueIdFilter());
		sort();
		refreshIdMap();
		fireTableDataChanged();
	}

	@Override
	public void listItemUpdated(String id) {
		Logger.info("List updated");
		Integer index = idIndexMap.get(id);
		if (index != null) {
			fireTableRowsUpdated(index, index);
		}
	}

	private void sort() {
		if (_sorter == null) {
			_sorter = new DownloadSorter();
		}
		idList.sort(_sorter);
	}

	private void refreshIdMap() {
		idIndexMap = new HashMap<>();
		for (int i = 0; i < idList.size(); i++) {
			idIndexMap.put(idList.get(i), i);
		}
	}

	public String getIdAt(int index) {
		return idList.get(index);
	}

	public int getIndexOfId(String id) {
		for (int i = 0; i < idList.size(); i++) {
			if (idList.get(i).equals(id))
				return i;
		}
		return -1;
	}

}
