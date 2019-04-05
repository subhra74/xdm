package xdman;

import java.util.ArrayList;
import java.util.Date;

import xdman.util.Logger;

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
		queuedItems = new ArrayList<String>();
		this.startTime = this.endTime = -1;
	}

	public boolean isRunning() {
		return running;
	}

	public void start() {
		if (running)
			return;
		index = 0;
		running = true;
		next();
	}

	public void stop() {
		running = false;
		XDMApp app = XDMApp.getInstance();
		for (int i = 0; i < queuedItems.size(); i++) {
			String id = queuedItems.get(i);
			DownloadEntry ent = app.getEntry(id);
			int state = ent.getState();
			if (state == XDMConstants.FAILED || state == XDMConstants.FINISHED || state == XDMConstants.PAUSED) {
				continue;
			} else {
				app.pauseDownload(id);
			}
		}
	}

	public synchronized void next() {
		Logger.log(queueId + " attmpting to process next item");
		if (!running)
			return;
		int c = 0;
		XDMApp app = XDMApp.getInstance();
		if (queuedItems == null)
			return;
		if (app.queueItemPending(queueId)) {
			Logger.log(queueId + " not processing as has already pending download");
			return;
		}
		if (currentItemId != null) {
			DownloadEntry ent = app.getEntry(currentItemId);
			if (ent != null) {
				int state = ent.getState();
				if (!(state == XDMConstants.FAILED || state == XDMConstants.PAUSED || state == XDMConstants.FINISHED)) {
					Logger.log(queueId + " not processing as has already active download");
					return;
				}
			}
		}
		Logger.log(queueId + " total queued " + queuedItems.size());
		if (!(index < queuedItems.size())) {
			index = 0;
		}
		for (; index < queuedItems.size();) {
			String id = queuedItems.get(index);
			DownloadEntry ent = app.getEntry(id);
			if (ent != null) {
				int state = ent.getState();
				if (state == XDMConstants.FAILED || state == XDMConstants.PAUSED) {
					Logger.log("index: " + index + " c: " + c);
					currentItemId = id;
					index++;
					ent.setStartedByUser(false);
					XDMApp.getInstance().resumeDownload(id, false);
					return;
				}
			}
			index++;
		}
	}

	public void removeFromQueue(String id) {
		int c = 0;
		XDMApp app = XDMApp.getInstance();
		for (int i = 0; i < queuedItems.size(); i++) {
			if (queuedItems.get(i).equals(id)) {
				c = i;
				if (c <= index) {
					index--;
				}
				queuedItems.remove(i);
				if (id.equals(currentItemId)) {
					currentItemId = null;
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
		if (!queuedItems.contains(id)) {
			Logger.log(id + " added to " + queueId);
			queuedItems.add(id);
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent != null) {
				ent.setQueueId(queueId);
			}
		}
		QueueManager.getInstance().saveQueues();
	}

	public final String getQueueId() {
		return queueId;
	}

	public final void setQueueId(String queueId) {
		this.queueId = queueId;
	}

	public final String getName() {
		return name;
	}

	public final void setName(String name) {
		this.name = name;
	}

	public ArrayList<String> getQueuedItems() {
		return queuedItems;
	}

	@Override
	public String toString() {
		return getName();
	}

	public final long getStartTime() {
		return startTime;
	}

	public final void setStartTime(long startTime) {
		this.startTime = startTime;
	}

	public final long getEndTime() {
		return endTime;
	}

	public final void setEndTime(long endTime) {
		this.endTime = endTime;
	}

	public final boolean isPeriodic() {
		return periodic;
	}

	public final void setPeriodic(boolean periodic) {
		this.periodic = periodic;
	}

	public final Date getExecDate() {
		return execDate;
	}

	public final void setExecDate(Date execDate) {
		this.execDate = execDate;
	}

	public final int getDayMask() {
		return dayMask;
	}

	public final void setDayMask(int dayMask) {
		this.dayMask = dayMask;
	}

	public final void setQueuedItems(ArrayList<String> queuedItems) {
		this.queuedItems = queuedItems;
	}

	public final synchronized void reorderItems(ArrayList<String> newOrder) {
		ArrayList<String> newList = new ArrayList<>();
		for (String s : newOrder) {
			newList.add(s);
		}
		for (String id : this.queuedItems) {
			if (!newList.contains(id)) {
				newList.add(id);
			}
		}
		this.queuedItems.clear();
		this.queuedItems.addAll(newList);
		// for (String s : newOrder) {
		// this.queuedItems.add(s);
		// }
	}

	public boolean hasPendingItems() {
		if (!running) {
			return false;
		}
		for (String id : queuedItems) {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent != null) {
				if (ent.getState() != XDMConstants.FINISHED) {
					return true;
				}
			}
		}
		return false;
	}
}
