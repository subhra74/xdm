package xdman;

import xdman.util.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

public class DownloadQueue {
    private boolean running;
    private String queueId;
    private int index;
    private String name;
    private ArrayDeque<String> queuedItems;
    private String currentItemId;
    private long startTime, endTime;
    private boolean periodic;
    private Date execDate;
    private int dayMask;

    public DownloadQueue(String queueId,
                         String name) {
        this.queueId = queueId;
        this.name = name;
        queuedItems = new ArrayDeque<>();
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
        for (String queuedItem : queuedItems) {
            DownloadEntry ent = app.getEntry(queuedItem);
            int state = ent.getState();
            if (state == XDMConstants.FAILED 
                    || state == XDMConstants.FINISHED
                    || state == XDMConstants.PAUSED) {
                continue;
            } else {
                app.pauseDownload(queuedItem);
            }
        }
    }

    public synchronized void next() {
        Logger.log( queueId,
                "total queued:", queuedItems.size(),
                "index:", index,
                "attempting to process next item...");
        if (!running)
            return;
        XDMApp xdmApp = XDMApp.getInstance();
        if (queuedItems == null)
            return;
        if (xdmApp.queueItemPending(queueId)) {
            Logger.log( queueId,
                    "total queued:", queuedItems.size(),
                    "index:", index,
                    "not processing as has already pending download");
            return;
        }
        if (currentItemId != null) {
            DownloadEntry ent = xdmApp.getEntry(currentItemId);
            if (ent != null) {
                int state = ent.getState();
                if (!(state == XDMConstants.FAILED
                        || state == XDMConstants.PAUSED
                        || state == XDMConstants.FINISHED)) {
                    Logger.log( queueId,
                            "total queued:", queuedItems.size(),
                            "index:", index,
                            "not processing as has already active download");
                    return;
                }
            }
        }
        Logger.log(queueId,
                "total queued:", queuedItems.size(),
                "index:", index);
        index = 0;
        for (Iterator<String> iterator = queuedItems.iterator(); iterator.hasNext(); index++) {
            String queuedItem = iterator.next();
            DownloadEntry downloadEntry = xdmApp.getEntry(queuedItem);
            if (downloadEntry != null) {
                int state = downloadEntry.getState();
                if (state == XDMConstants.FAILED 
                        || state == XDMConstants.PAUSED) {
                    Logger.log( queueId,
                            "total queued:", queuedItems.size(),
                            "index:", index,
                            "currentItemId:", currentItemId);
                    currentItemId = queuedItem;
                    downloadEntry.setStartedByUser(false);
                    XDMApp.getInstance().resumeDownload(queuedItem, false);
                    return;
                }
            }
        }
    }

    public void addToQueue(String queuedItem) {
        if (!queuedItems.contains(queuedItem)) {
            queuedItems.add(queuedItem);
            index++;
            Logger.log(queuedItem,
                    "added to", queueId,
                    "total queued:", queuedItems.size(),
                    "index:", index);
            DownloadEntry downloadEntry = XDMApp.getInstance().getEntry(queuedItem);
            if (downloadEntry != null) {
                downloadEntry.setQueueId(queueId);
            }
        }
        QueueManager.getInstance().saveQueues();
    }

    public void removeFromQueue(String queuedItem) {
        XDMApp app = XDMApp.getInstance();
        if (queuedItems.contains(queuedItem)) {
            queuedItems.remove(queuedItem);
            index--;
            if (queuedItem.equals(currentItemId)) {
                currentItemId = null;
            }
            DownloadEntry ent = app.getEntry(queuedItem);
            if (ent != null) {
                ent.setQueueId("");
            }
            QueueManager.getInstance().saveQueues();
            return;
        }
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

    public ArrayDeque<String> getQueuedItems() {
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

    public final void setQueuedItems(ArrayDeque<String> queuedItems) {
        this.queuedItems = queuedItems;
    }

    public final synchronized void reorderItems(ArrayList<String> newOrder) {
        ArrayList<String> newList = new ArrayList<>();
        for (String s : newOrder) {
            newList.add(s);
        }
        for (String queuedItem : this.queuedItems) {
            if (!newList.contains(queuedItem)) {
                newList.add(queuedItem);
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
        for (String queuedItem : queuedItems) {
            DownloadEntry ent = XDMApp.getInstance().getEntry(queuedItem);
            if (ent != null) {
                if (ent.getState() != XDMConstants.FINISHED) {
                    return true;
                }
            }
        }
        return false;
    }
}
