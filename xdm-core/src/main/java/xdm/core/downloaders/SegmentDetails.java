package xdm.core.downloaders;

import java.util.ArrayList;
import java.util.List;

public class SegmentDetails {
  private final ArrayList<SegmentInfo> segInfoList;
  private long chunkCount;

  public SegmentDetails() {
    segInfoList = new ArrayList<>();
  }

  public final List<SegmentInfo> getChunkUpdates() {
    return segInfoList;
  }

  public final synchronized long getChunkCount() {
    return chunkCount;
  }

  public final synchronized void setChunkCount(long chunkCount) {
    this.chunkCount = chunkCount;
  }

  public final synchronized void extend(int len) {
    for (int i = 0; i < len; i++) {
      segInfoList.add(new SegmentInfo());
    }
  }

  public int getCapacity() {
    return segInfoList.size();
  }
}
