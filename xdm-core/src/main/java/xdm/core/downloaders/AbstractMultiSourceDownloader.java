package xdm.core.downloaders;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.Config;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.constants.ErrorCode;
import xdm.core.media.muxer.Muxer;
import xdm.core.media.muxer.impl.FFmpegMuxer;
import xdm.core.network.http.impl.PoolingHttpClientImpl;
import xdm.core.util.StringUtils;

public abstract class AbstractMultiSourceDownloader extends AbstractDownloader {
  private static final Logger logger = LoggerFactory.getLogger(AbstractMultiSourceDownloader.class);
  protected final AtomicBoolean init = new AtomicBoolean(false);
  protected DownloadProgressData downloadProgressData;
  protected final ExecutorService executorService;
  protected final PoolingHttpClientImpl httpClient = new PoolingHttpClientImpl(100);
  protected List<DownloadablePiece> pieces;
  protected List<PieceGrabber> pieceGrabbers;
  protected boolean hasSeparateStreams = false;
  protected final Muxer muxer = new FFmpegMuxer();
  private long lastUpdatePosted;
  protected final AtomicBoolean finished = new AtomicBoolean(false);

  protected AbstractMultiSourceDownloader(
      DownloaderType downloaderType,
      long id,
      String folder,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    super(id, downloaderType, listener, credentialProvider);
    this.folder = new File(folder, String.valueOf(id)).getAbsolutePath();
    this.length.set(-1);
    this.maxCount = Config.getInstance().getMaxSegments();
    this.executorService = Executors.newFixedThreadPool(this.maxCount);
    this.eta = -1;
    this.downloadProgressData = new DownloadProgressData(this, 0);
  }

  public void start() {
    logger.info("creating folder {}", folder);
    new File(folder).mkdirs();
    this.downloadProgressData.setTotalDownloadedBytes(0L);
    this.downloadProgressData.setLastProgress(0);
    this.progress.set(0);
    new Thread(this::download).start();
  }

  @Override
  public void resume() {
    stopFlag.set(false);
    logger.info("Resuming");
    if (!restoreState()) {
      logger.info("Starting from beginning");
      start();
      return;
    }
    this.download();
  }

  private void download() {
    try {
      if (!this.init.get()) {
        this.initDownload();
        this.init.set(true);
        this.getMetadata().setFileName(fixExtension(this.getMetadata().getFileName()));
        this.saveState();
        this.listener.downloadConfirmed(id);
      }
      this.downloadChunks();
      this.saveState();
      this.assemble();
      this.saveState();
      this.listener.downloadFinished(id);
    } catch (IOException e) {
      this.listener.downloadFailed(id, ErrorCode.RESUME_FAILED);
    }
  }

  protected abstract String fixExtension(String fileName);

  private void assemble() {
    this.assembling = true;
    String outFile = new File(getOutputFolder(), getOutputFileName()).getAbsolutePath();
    if (this.hasSeparateStreams) {
      assembleChunksWithSeparateStreams(outFile);
    } else {
      assembleChunksWithSingleStreams(outFile);
    }
  }

  private void assembleChunksWithSeparateStreams(String outFile) {
    logger.info("Assembling separate streams");
    try {
      boolean ret =
          muxer.mux(
              this.pieces.stream()
                  .filter(c -> StringUtils.containsIgnoreCase("AUDIO", c.getTag()))
                  .map(c -> new File(this.folder, c.getId()).getAbsolutePath())
                  .collect(Collectors.toList()),
              this.pieces.stream()
                  .filter(c -> StringUtils.containsIgnoreCase("VIDEO", c.getTag()))
                  .map(c -> new File(this.folder, c.getId()).getAbsolutePath())
                  .collect(Collectors.toList()),
              outFile,
              prg -> this.listener.downloadUpdated(this.id),
              this.folder);
      if (ret) {
        cleanup();
        this.finished.set(true);
        listener.downloadFinished(this.id);
      } else {
        listener.downloadFailed(this.id, ErrorCode.FFMPEG_FAILED);
      }
    } catch (Exception ex) {
      logger.error("Error in muxing", ex);
      listener.downloadFailed(this.id, ErrorCode.FFMPEG_FAILED);
    }
  }

  private void assembleChunksWithSingleStreams(String outFile) {
    try {
      boolean ret =
          muxer.mux(
              this.pieces.stream()
                  .filter(c -> StringUtils.containsIgnoreCase("VIDEO", c.getTag()))
                  .map(c -> new File(this.folder, c.getId()).getAbsolutePath())
                  .collect(Collectors.toList()),
              outFile,
              prg -> this.listener.downloadUpdated(this.id),
              this.folder);
      if (ret) {
        cleanup();
        this.finished.set(true);
        listener.downloadFinished(this.id);
      } else {
        listener.downloadFailed(this.id, ErrorCode.FFMPEG_FAILED);
      }
    } catch (Exception ex) {
      logger.error("Error in muxing", ex);
      listener.downloadFailed(this.id, ErrorCode.FFMPEG_FAILED);
    }
  }

  protected abstract void downloadChunks() throws IOException;

  protected abstract void initDownload() throws IOException;

  @Override
  public void stop() {
    this.stopFlag.set(true);
    this.speedLimiter.wakeIfSleeping();
    this.muxer.stop();
    this.executorService.shutdownNow();
    if (this.pieceGrabbers != null) {
      for (PieceGrabber pg : this.pieceGrabbers) {
        pg.stop();
      }
    }
    this.saveState();
    this.listener.downloadStopped(this.id);
  }

  @Override
  public int getType() {
    return 0;
  }

  @Override
  public SegmentDetails getSegmentDetails() {
    return null;
  }

  protected abstract void saveState();

  protected abstract boolean restoreState();

  protected void onDownloadProgress(long bytes) {
    long now = System.currentTimeMillis();
    this.downloaded.getAndAdd(bytes);
    this.throttle();
    this.downloadProgressData.updateDownloadInfo(bytes, null);
    if (now - this.lastUpdatePosted > 1000) {
      listener.downloadUpdated(id);
      this.lastUpdatePosted = now;
    }
  }
}
