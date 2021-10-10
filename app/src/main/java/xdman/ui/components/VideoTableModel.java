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

import javax.swing.table.AbstractTableModel;

import xdman.videoparser.YdlResponse.YdlVideo;

public class VideoTableModel extends AbstractTableModel {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1338853497580208127L;

	public VideoTableModel() {
		list = new ArrayList<>();
	}

	public void setList(ArrayList<VideoItemWrapper> list) {
		this.list.clear();
		this.list.addAll(list);
		this.fireTableDataChanged();
	}

	public ArrayList<YdlVideo> getSelectedVideoList() {
		ArrayList<YdlVideo> selectedList = new ArrayList<>();
		for (int i = 0; i < this.list.size(); i++) {
			VideoItemWrapper w = list.get(i);
			if (w.checked) {
				selectedList.add(w.videoItem);
			}
		}
		return selectedList;
	}

	ArrayList<VideoItemWrapper> list;

	@Override
	public int getColumnCount() {
		return 1;
	}

	@Override
	public int getRowCount() {
		return list.size();
	}

	@Override
	public Object getValueAt(int r, int c) {
		return list.get(r);
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		return VideoItemWrapper.class;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return true;
	}

	@Override
	public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
		list.set(rowIndex, (VideoItemWrapper) aValue);
	}

	public void clear() {
		list.clear();
		fireTableDataChanged();
	}

}

class VideoItemWrapper {
	boolean checked;
	YdlVideo videoItem;
}
