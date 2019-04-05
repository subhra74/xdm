package xdman.downloaders;

import java.util.ArrayList;

public class SegmentDetails {
	ArrayList<SegmentInfo> segInfoList;
	private long chunkCount;

	public SegmentDetails() {
		segInfoList = new ArrayList<SegmentInfo>();
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
