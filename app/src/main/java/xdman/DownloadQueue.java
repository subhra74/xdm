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

package xdman;

import java.util.ArrayList;
import java.util.Date;

import org.tinylog.Logger;

@SuppressWarnings("unused")
public class DownloadQueue {
	private boolean running;
	private String queueId;
	private int index;
	private String name;
	private ArrayList<String> queuedItems;
	private String currentItemId;
	private long startTime, endTime;
	private boolean periodic;
	private Date execDate;
	private int dayMask;

	public DownloadQueue(String id, String name) {
		this.name = name;
		this.queueId = id;
		this.queuedItems = new ArrayList<>();
		this.startTime = this.endTime = -1;
	}

	public boolean isRunning() {
		return this.running;
	}

	public void start() {
		if (this.running)
			return;
		this.index = 0;
		this.running = true;
		this.next();
	}

	public void stop() {
		this.running = false;
		XDMApp app = XDMApp.getInstance();
		for (String id : this.queuedItems) {
			DownloadEntry ent = app.getEntry(id);
			int state = ent.getState();
			if (state == XDMConstants.FAILED || state == XDMConstants.FINISHED || state == XDMConstants.PAUSED) {
				Logger.warn("Id: " + id + ", state: " + state);
			} else {
				app.pauseDownload(id);
			}
		}
	}

	public synchronized void next() {
		Logger.info(this.queueId + " attempting to process next item");
		if (!this.running)
			return;
		XDMApp app = XDMApp.getInstance();
		if (this.queuedItems == null)
			return;
		if (app.queueItemPending(this.queueId)) {
			Logger.warn(this.queueId + " not processing as has already pending download");
			return;
		}
		if (this.currentItemId != null) {
			DownloadEntry ent = app.getEntry(this.currentItemId);
			if (ent != null) {
				int state = ent.getState();
				if (!(state == XDMConstants.FAILED || state == XDMConstants.PAUSED || state == XDMConstants.FINISHED)) {
					Logger.warn(this.queueId + " not processing as has already active download");
					return;
				}
			}
		}
		Logger.info(this.queueId + " total queued " + this.queuedItems.size());
		if (!(this.index < this.queuedItems.size())) {
			this.index = 0;
		}
		while (this.index < this.queuedItems.size()) {
			String id = this.queuedItems.get(this.index);
			DownloadEntry entry = app.getEntry(id);
			if (entry != null) {
				int state = entry.getState();
				if (state == XDMConstants.FAILED || state == XDMConstants.PAUSED) {
					Logger.info("index: " + this.index + " c: " + 0);
					this.currentItemId = id;
					this.index++;
					entry.setStartedByUser(false);
					XDMApp.getInstance().resumeDownload(id, false);
					return;
				}
			}
			this.index++;
		}
	}

	public void removeFromQueue(String id) {
		int counter;
		XDMApp app = XDMApp.getInstance();
		for (int i = 0; i < this.queuedItems.size(); i++) {
			if (this.queuedItems.get(i).equals(id)) {
				counter = i;
				if (counter <= this.index) {
					this.index--;
				}
				this.queuedItems.remove(i);
				if (id.equals(this.currentItemId)) {
					this.currentItemId = null;
				}
				DownloadEntry ent = app.getEntry(id);
				if (ent != null) {
					ent.setQueueId("");
				}
				QueueManager.getInstance().saveQueues();
				return;
			}
		}
	}

	public void addToQueue(String id) {
		if (!this.queuedItems.contains(id)) {
			Logger.info(id + " added to " + this.queueId);
			this.queuedItems.add(id);
			DownloadEntry entry = XDMApp.getInstance().getEntry(id);
			if (entry != null) {
				entry.setQueueId(this.queueId);
			}
		}
		QueueManager.getInstance().saveQueues();
	}

	public final String getQueueId() {
		return this.queueId;
	}

	public final void setQueueId(String queueId) {
		this.queueId = queueId;
	}

	public final String getName() {
		return this.name;
	}

	public final void setName(String name) {
		this.name = name;
	}

	public ArrayList<String> getQueuedItems() {
		return this.queuedItems;
	}

	@Override
	public String toString() {
		return getName();
	}

	public final long getStartTime() {
		return this.startTime;
	}

	public final void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public final long getEndTime() {
		return this.endTime;
	}

	public final void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	public final boolean isPeriodic() {
		return this.periodic;
	}

	public final void setPeriodic(boolean periodic) {
		this.periodic = periodic;
	}

	public final Date getExecDate() {
		return this.execDate;
	}

	public final void setExecDate(Date execDate) {
		this.execDate = execDate;
	}

	public final int getDayMask() {
		return this.dayMask;
	}

	public final void setDayMask(int dayMask) {
		this.dayMask = dayMask;
	}

	public final void setQueuedItems(ArrayList<String> queuedItems) {
		this.queuedItems = queuedItems;
	}

	public final synchronized void reorderItems(ArrayList<String> newOrder) {
		ArrayList<String> newList = new ArrayList<>(newOrder);
		for (String id : this.queuedItems) {
			if (!newList.contains(id)) {
				newList.add(id);
			}
		}
		this.queuedItems.clear();
		this.queuedItems.addAll(newList);
	}

	public boolean hasPendingItems() {
		if (!this.running) {
			return false;
		}
		for (String id : this.queuedItems) {
			DownloadEntry entry = XDMApp.getInstance().getEntry(id);
			if (entry != null) {
				if (entry.getState() != XDMConstants.FINISHED) {
					return true;
				}
			}
		}
		return false;
	}
}
