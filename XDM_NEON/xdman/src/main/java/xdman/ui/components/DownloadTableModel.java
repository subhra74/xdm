package xdman.ui.components;

import java.util.*;

import javax.swing.table.*;

import xdman.*;
import xdman.util.Logger;

public class DownloadTableModel extends AbstractTableModel implements ListChangeListener {
	private static final long serialVersionUID = 5474784018135644748L;
	ArrayList<String> idList;
	Map<String, Integer> idIndexMap;
	DownloadSorter _sorter;

	public DownloadTableModel() {
		idList = new ArrayList<String>();
		idIndexMap = new HashMap<String, Integer>();
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
		Logger.log("List changed");
		idList = XDMApp.getInstance().getDownloadList(Config.getInstance().getCategoryFilter(),
				Config.getInstance().getStateFilter(), Config.getInstance().getSearchText(),
				Config.getInstance().getQueueIdFilter());
		sort();
		refreshIdMap();
		fireTableDataChanged();
	}

	@Override
	public void listItemUpdated(String id) {
		Logger.log("List updated");
		Integer index = idIndexMap.get(id);
		if (index != null) {
			fireTableRowsUpdated(index, index);
		}
	}

	private void sort() {
		if (_sorter == null) {
			_sorter = new DownloadSorter();
		}
		Collections.sort(idList, _sorter);
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
