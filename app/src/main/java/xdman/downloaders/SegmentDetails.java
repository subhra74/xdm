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

package xdman.downloaders;

import java.util.ArrayList;

public class SegmentDetails {

	private final ArrayList<SegmentInfo> segInfoList;
	private long chunkCount;

	public SegmentDetails() {
		segInfoList = new ArrayList<>();
	}

	public final ArrayList<SegmentInfo> getChunkUpdates() {
		return segInfoList;
	}

	public synchronized final long getChunkCount() {
		return chunkCount;
	}

	public synchronized final void setChunkCount(long chunkCount) {
		this.chunkCount = chunkCount;
	}

	public synchronized final void extend(int len) {
		for (int i = 0; i < len; i++) {
			segInfoList.add(new SegmentInfo());
		}
	}

	public int getCapacity() {
		return segInfoList.size();
	}

}
