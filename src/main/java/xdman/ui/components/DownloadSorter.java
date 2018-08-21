package xdman.ui.components;

import xdman.Config;
import xdman.DownloadEntry;
import xdman.XDMApp;
import xdman.util.Logger;

import java.util.Comparator;

public class DownloadSorter implements Comparator<String> {

	@Override
	public int compare(String id1, String id2) {
		DownloadEntry o1 = XDMApp.getInstance().getEntry(id1);
		DownloadEntry o2 = XDMApp.getInstance().getEntry(id2);
		int res = 0;
		Config config = Config.getInstance();
		int sortField = config.getSortField();
		boolean sortAsc = config.getSortAsc();
		Logger.log(sortField,
				sortAsc);
		switch (sortField) {
			case Config.SORT_BY_DATE:
			res = o1.getDate() > o2.getDate() ? 1 : -1;
			break;
			case Config.SORT_BY_SIZE:
			res = o1.getSize() > o2.getSize() ? 1 : -1;
			break;
			case Config.SORT_BY_NAME:
			res = o1.getFile().compareTo(o2.getFile());
			break;
			case Config.SORT_BY_TYPE:
			res = o1.getCategory() - o2.getCategory();
			break;
		default:
			break;
		}
		if (sortAsc) {
			// asc
			return res;
		} else {
			// desc
			return -res;
		}
	}
}
