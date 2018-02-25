package xdman.ui.components;

import java.util.Comparator;

import xdman.*;

public class DownloadSorter implements Comparator<String> {

	@Override
	public int compare(String id1, String id2) {
		DownloadEntry o1 = XDMApp.getInstance().getEntry(id1);
		DownloadEntry o2 = XDMApp.getInstance().getEntry(id2);
		int res = 0;
		//System.out.println(Config.getInstance().getSortField()+" "+Config.getInstance().getSortAsc());
		switch (Config.getInstance().getSortField()) {
		case 0:// sort by date
			res = o1.getDate() > o2.getDate() ? 1 : -1;
			break;
		case 1:// sort by size
			res = o1.getSize() > o2.getSize() ? 1 : -1;
			break;
		case 2:// sort by name
			res = o1.getFile().compareTo(o2.getFile());
			break;
		case 3:// sort by type
			res = o1.getCategory() - o2.getCategory();
			break;
		default:
			break;
		}
		if (Config.getInstance().getSortAsc()) {
			// asc
			return res;
		} else {
			// desc
			return -res;
		}
	}
}
