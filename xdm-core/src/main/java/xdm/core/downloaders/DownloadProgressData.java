package xdm.core.downloaders;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import xdm.core.util.FormatUtilities;

public class DownloadProgressData {
  private final AbstractDownloader downloader;
  @Setter private long totalDownloadedBytes;
  private long lastDownloadedBytes;
  private final LinkedList<Double> speedHistory;
  private double speedSum;
  private long downloadedBytesSinceStartOrResume,
      ticksAtDownloadStartOrResume,
      lastProgressUpdatedAt,
      lastChunkStatsUpdated;
  @Getter private int progress;
  @Getter private float downloadSpeed;
  @Getter private long eta;
  @Getter private SegmentDetails segmentDetails;
  @Setter private int lastProgress;
  private final EnumSet<DownloaderType> adaptiveDownloaderTypes =
      EnumSet.of(DownloaderType.Hls, DownloaderType.Dash, DownloaderType.EncryptedHls);

  public DownloadProgressData(AbstractDownloader downloader, long totalDownloadedBytes) {
    this.downloader = downloader;
    this.totalDownloadedBytes = totalDownloadedBytes;
    this.ticksAtDownloadStartOrResume = System.currentTimeMillis();
    this.downloadedBytesSinceStartOrResume = 0;
    this.speedHistory = new LinkedList<>();
  }

  public synchronized void updateDownloadInfo(long downloadedBytes, List<Chunk> chunks) {
    long ticks = System.currentTimeMillis();
    this.totalDownloadedBytes += downloadedBytes;
    this.downloadedBytesSinceStartOrResume += downloadedBytes;
    long ticksElapsed = ticks - this.ticksAtDownloadStartOrResume;
    if (ticks - this.lastProgressUpdatedAt > 500 && ticksElapsed > 0) {
      double instantSpeed =
          ((this.totalDownloadedBytes - this.lastDownloadedBytes) * 1000)
              / (double) (ticks - this.lastProgressUpdatedAt);
      if (this.speedHistory.size() > 5) {
        this.speedSum -= this.speedHistory.removeFirst();
      }
      this.speedHistory.add(instantSpeed);
      speedSum += instantSpeed;
      double instanceSpeedBounded = speedSum / this.speedHistory.size();
      double avgSpeed = (this.downloadedBytesSinceStartOrResume * 1000.0) / ticksElapsed;
      this.lastProgressUpdatedAt = ticks;
      this.lastDownloadedBytes = this.totalDownloadedBytes;
      this.progress = downloader.getProgress().get();
      this.downloadSpeed = (float) instanceSpeedBounded;
      if (adaptiveDownloaderTypes.contains(downloader.getDownloaderType())) {
        int prgDiff = progress - lastProgress;
        lastProgress = progress;
        if (prgDiff > 0) {
          this.eta = (ticksElapsed * (100 - progress) / 1000 * prgDiff);
        }
      } else {
        this.eta =
            FormatUtilities.getEtaAsSec(
                (double) downloader.getSize() - totalDownloadedBytes, (float) avgSpeed);
      }
    }
    if (ticks - this.lastChunkStatsUpdated > 1000 && ticksElapsed > 0) {
      this.updateChunkProgressData(chunks);
      this.lastChunkStatsUpdated = ticks;
    }
  }

  private void updateChunkProgressData(List<Chunk> chunks) {
    if (segmentDetails == null) {
      segmentDetails = new SegmentDetails();
    }
    if (this.adaptiveDownloaderTypes.contains(this.downloader.getDownloaderType())) {
      if (segmentDetails.getCapacity() < downloader.getMaxCount()) {
        segmentDetails.extend(downloader.getMaxCount());
      }
      int prg = downloader.getProgress().get();
      for (int i = 0; i < downloader.getMaxCount(); i++) {
        SegmentInfo info = segmentDetails.getChunkUpdates().get(i);
        info.setDownloaded(prg);
        info.setStart(i * 100L);
        info.setLength(100);
      }
    } else {
      if (chunks == null) {
        return;
      }
      if (segmentDetails.getCapacity() < chunks.size()) {
        segmentDetails.extend(chunks.size() - segmentDetails.getCapacity());
      }
      segmentDetails.setChunkCount(chunks.size());
      for (int i = 0; i < chunks.size(); i++) {
        Chunk s = chunks.get(i);
        SegmentInfo info = segmentDetails.getChunkUpdates().get(i);
        info.setDownloaded(s.getDownloaded());
        info.setStart(s.getStartOffset());
        info.setLength(s.getLength());
      }
    }
  }
}
