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

import java.util.Comparator;

import xdman.Config;
import xdman.DownloadEntry;
import xdman.XDMApp;

public class DownloadSorter implements Comparator<String> {

	@Override
	public int compare(String id1, String id2) {
		DownloadEntry o1 = XDMApp.getInstance().getEntry(id1);
		DownloadEntry o2 = XDMApp.getInstance().getEntry(id2);
		int res = 0;
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
			return res;
		} else {
			return -res;
		}
	}

}
