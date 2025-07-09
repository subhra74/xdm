package xdm.core.downloaders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.Config;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.constants.ErrorCode;
import xdm.core.downloaders.http.HttpChunkRetriever;
import xdm.core.util.CollectionUtils;
import xdm.core.util.StringUtils;
import xdm.core.util.XDMUtils;

public abstract class AbstractSegmentedDownloader extends AbstractDownloader
    implements ChunkUpdateListener {
  private static final Logger logger = LoggerFactory.getLogger(AbstractSegmentedDownloader.class);
  private final AtomicBoolean init = new AtomicBoolean(false);
  private final int minChunkSize;
  private final AtomicBoolean assembleFinished = new AtomicBoolean(false);
  private long totalAssembled;
  private final DownloadProgressData downloadProgressData;
  protected ArrayList<Chunk> chunks;
  private static final int MIN_TAKEOVER_DELAY = 5000;

  protected AbstractSegmentedDownloader(
      DownloaderType downloaderType,
      long id,
      String tempFolder,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    super(id, downloaderType, listener, credentialProvider);
    this.folder = new File(tempFolder, String.valueOf(id)).getAbsolutePath();
    this.length.set(-1);
    this.maxCount = Config.getInstance().getMaxSegments();
    this.minChunkSize = Config.getInstance().getMinSegmentSize();
    this.eta = -1;
    this.downloadProgressData = new DownloadProgressData(this, 0);
  }

  public void start() {
    logger.info("creating folder {}", folder);
    new File(folder).mkdirs();
    this.chunks = new ArrayList<>();
    this.downloadProgressData.setTotalDownloadedBytes(0L);
    try {
      Chunk c1 = new ChunkImpl(this, folder);
      // handle case of single dash stream
      //      if (getMetadata() instanceof DashMetadata) {
      //        c1.setTag("T1");
      //      }
      c1.setLength(-1);
      c1.setStartOffset(0);
      c1.setDownloaded(0);
      chunks.add(c1);
      c1.download(this);
    } catch (IOException e) {
      this.listener.downloadFailed(id, ErrorCode.RESUME_FAILED);
    }
  }

  @Override
  public void resume() {
    try {
      stopFlag.set(false);
      logger.info("Resuming");
      if (!restoreState()) {
        logger.info("Starting from beginning");
        start();
        return;
      }
      this.downloadProgressData.setTotalDownloadedBytes(downloaded.get());
      logger.info("Restore success");
      init.set(true);
      Chunk c1 = findInactiveChunk();
      if (c1 != null) {
        resumeInternal(c1);
      } else if (allFinished()) {
        assembleAsync();
      } else {
        logger.error("Internal error: no inactive/incomplete chunk found while resuming!");
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
      listener.downloadFailed(this.id, ErrorCode.RESUME_FAILED);
    }
  }

  private void resumeInternal(Chunk c1) {
    try {
      c1.download(this);
    } catch (Exception e) {
      logger.error("Error in Resume", e);
      if (!stopFlag.get()) {
        logger.error(e.getMessage(), e);
        listener.downloadFailed(this.id, ErrorCode.RESUME_FAILED);
      }
    }
  }

  private synchronized void createChunk() throws IOException {
    if (stopFlag.get()) return;
    int activeCount = getActiveChunkCount();
    logger.info("active count: {}", activeCount);
    if (activeCount == maxCount) {
      return;
    }

    int rem = maxCount - activeCount;

    rem -= retryFailedChunks(rem);

    if (rem > 0) {
      Chunk c1 = findMaxChunk();
      Chunk c = splitChunk(c1);
      if (c != null) {
        logger.info("creating chunk {}", c.getId());
        chunks.add(c);
        c.download(this);
      }
    }
  }

  private Chunk findMaxChunk() {
    if (stopFlag.get()) return null;
    long size = -1;
    long id = -1;
    long time = System.currentTimeMillis();
    for (Chunk c : chunks) {
      if (c.isActive() && time - c.getLastTakeOverTime() > MIN_TAKEOVER_DELAY) {
        long rem = c.getLength() - c.getDownloaded();
        if (rem > size) {
          id = c.getId();
          size = rem;
        }
      }
    }
    if (size < minChunkSize) return null;
    return getById(id);
  }

  // merge c2 into c1
  private void mergeChunk(Chunk c1, Chunk c2) {
    c1.setLength(c1.getLength() + c2.getLength());
  }

  private Chunk splitChunk(Chunk c) {
    if (c == null || stopFlag.get()) return null;
    long rem = c.getLength() - c.getDownloaded();
    long offset = c.getStartOffset() + c.getLength() - rem / 2;
    long len = rem / 2;
    logger.info("Changing length from: {} {}", c.getLength(), (c.getLength() - rem / 2));
    c.setLength(c.getLength() - rem / 2);
    Chunk c2 = new ChunkImpl(this, folder);
    // handle case of single dash stream
    //    if (getMetadata() instanceof DashMetadata) {
    //      c2.setTag("T1");
    //    }
    c2.setLength(len);
    c2.setStartOffset(offset);
    return c2;
  }

  private Chunk findChunkToTakeOver(Chunk chunk) {
    if (stopFlag.get()) return null;
    long offset = chunk.getStartOffset() + chunk.getLength();
    long actualRange = 0;
    if (chunk.getChunkRetriever() instanceof HttpChunkRetriever) {
      actualRange = ((HttpChunkRetriever) chunk.getChunkRetriever()).getActualRange();
    }
    for (int i = 0; i < chunks.size(); i++) {
      Chunk c = chunks.get(i);
      if (c.getDownloaded() == 0
          && (!c.isFinished()
              && (c.getStartOffset() == offset
                  && (c.getStartOffset() + c.getLength()) <= actualRange))) {
        return c;
      }
    }
    return null;
  }

  private void finishDownload() {
    finished.set(true);
    updateStatus(0);
    try {
      assemble();
      if (!assembleFinished.get()) {
        throw new IOException("Assemble failed");
      }
      logger.info("Download finished");
      updateStatus(0);
      listener.downloadFinished(this.id);
    } catch (Exception e) {
      if (!stopFlag.get()) {
        logger.error(e.getMessage(), e);
        listener.downloadFailed(this.id, ErrorCode.ERR_ASM_FAILED);
      }
    }
  }

  private synchronized boolean onComplete(long id) throws IOException {
    if (allFinished() || length.get() < 0) {
      // finish
      finishDownload();
      return true;
    }
    Chunk chunk = getById(id);
    logger.info(
        "Complete: {} downloaded: {} Length: {}",
        chunk.getId(),
        chunk.getDownloaded(),
        chunk.getLength());
    Chunk nextNeedyChunk = findChunkToTakeOver(chunk);
    if (nextNeedyChunk != null) {
      logger.info("Needy chunk found!!!");
      logger.info("Stopping: {}", nextNeedyChunk.getId());
      nextNeedyChunk.stop();
      chunks.remove(nextNeedyChunk);
      mergeChunk(chunk, nextNeedyChunk);
      chunk.setLastTakeOverTime(System.currentTimeMillis());
      createChunk();
      return false;
    }
    clearChannel(chunk);
    createChunk();
    return true;
  }

  @Override
  public synchronized void chunkInitiated(long id) throws IOException {
    if (stopFlag.get()) return;
    if (!init.get()) {
      Chunk c = getById(id);
      this.length.set(c.getLength());
      init.set(true);
      logger.info("Download size: {}", this.length);
      this.getLastModifiedDate(c);
      saveState();
      chunkConfirmed(c);
      listener.downloadConfirmed(this.id);
    }
    if (length.get() > 0) {
      createChunk();
    }
  }

  @Override
  public synchronized boolean chunkComplete(long id) throws IOException {
    if (finished.get()) {
      return true;
    }

    if (stopFlag.get()) {
      return true;
    }

    saveState();

    return onComplete(id);
  }

  @Override
  public void chunkUpdated(long id, int bytes) {
    if (stopFlag.get()) return;
    this.downloaded.getAndAdd(bytes);
    this.throttle();
    long now = System.currentTimeMillis();
    if (now - lastSaved > 5000) {
      synchronized (this) {
        saveState();
      }
      lastSaved = now;
    }
    updateStatus(bytes);
    if (now - lastUpdated > 1000) {
      lastUpdated = now;
      synchronized (this) {
        int activeCount = getActiveChunkCount();
        if (activeCount < maxCount) {
          int rem = maxCount - activeCount;
          try {
            retryFailedChunks(rem);
          } catch (Exception e) {
            logger.info(e.getMessage(), e);
          }
        }
      }
    }
  }

  private String decideOutFileName() {
    String outFileFinal = getOutputFileName();
    return (outputFormat == 0
        ? UUID.randomUUID().toString() + "_" + outFileFinal
        : UUID.randomUUID().toString());
  }

  private String decideOutputFolder() {
    return (outputFormat == 0 ? getOutputFolder() : folder);
  }

  private void copyBytes(final FileChannel inChannel, final FileChannel outChannel, long limit)
      throws IOException {
    long block1M = 1048576;
    long pos = 0;
    long rem = limit > 0 ? limit : inChannel.size();
    while (!stopFlag.get() && rem > 0) {
      long x = inChannel.transferTo(pos, Math.min(block1M, rem), outChannel);
      rem -= x;
      pos += x;
      totalAssembled += x;
      long now = System.currentTimeMillis();
      if (now - lastUpdated > 3000) {
        updateStatus(0);
        lastUpdated = now;
      }
    }
    if (!stopFlag.get() && limit > 0 && pos != limit) {
      logger.error("Chunk file is shorter than chunk size, possible file corruption");
      throw new IllegalArgumentException("Assemble EOF");
    }
  }

  private void copyChunks(final FileChannel outChannel) throws IOException {
    for (int i = 0; i < chunks.size(); i++) {
      logger.info("Assembling chunk: {}", i);
      Chunk c = chunks.get(i);
      try (FileChannel inChannel =
          FileChannel.open(
              new File(folder, String.valueOf(c.getId())).toPath(), StandardOpenOption.READ)) {
        copyBytes(inChannel, outChannel, c.getLength());
      }
      if (stopFlag.get()) {
        return;
      }
    }
  }

  //  private void joinChunks(final ByteBuffer buffer, final FileChannel outChannel)
  //      throws IOException {
  //    for (int i = 0; i < chunks.size(); i++) {
  //      logger.info("chunk {} {}", i, stopFlag);
  //      Chunk c = chunks.get(i);
  //      try (FileChannel inChannel =
  //          FileChannel.open(new File(folder, c.getId()).toPath(), StandardOpenOption.READ)) {
  //        long rem = c.getLength();
  //        while (!stopFlag.get()) {
  //          int x = rem > 0 ? (int) Math.min(rem, buffer.capacity()) : buffer.capacity();
  //          buffer.limit(x);
  //          int r = inChannel.read(buffer);
  //          if (stopFlag.get()) {
  //            return;
  //          }
  //          if (r == -1) {
  //            if (length.get() > 0) {
  //              throw new IllegalArgumentException("Assemble EOF");
  //            } else {
  //              break;
  //            }
  //          }
  //          buffer.flip();
  //          outChannel.write(buffer);
  //          buffer.clear();
  //          if (stopFlag.get()) {
  //            return;
  //          }
  //          if (length.get() > 0) {
  //            rem -= r;
  //            if (rem == 0) break;
  //          }
  //          totalAssembled += r;
  //          long now = System.currentTimeMillis();
  //          if (now - lastUpdated > 1000) {
  //            updateStatus(0);
  //            lastUpdated = now;
  //          }
  //        }
  //      }
  //    }
  //  }

  private void assemble() throws IOException {
    totalAssembled = 0L;
    assembling = true;
    assembleFinished.set(false);
    String outFileName = decideOutFileName();
    String outputFolder = decideOutputFolder();
    XDMUtils.mkdirs(getOutputFolder());
    File outFile = new File(outputFolder, outFileName);
    File ffOutFile = null;
    try {
      if (stopFlag.get()) return;
      logger.info("assembling... ");
      Collections.sort(chunks, new SegmentComparator());
      try (FileChannel outChannel =
          FileChannel.open(
              outFile.toPath(),
              CollectionUtils.setOf(
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING))) {
        copyChunks(outChannel);
        if (stopFlag.get()) {
          return;
        }
      }
      setLastModifiedDate(outFile);
      updateStatus(0);
      // delete the original file if exists and rename the temp file to original
      File realFile = new File(getOutputFolder(), getOutputFileName());
      delete(realFile);

      renameTo(outFile, realFile);
      setLastModifiedDate(outFile);

      updateStateFinal(outFileName, outputFolder, totalAssembled);

      assembleFinished.set(true);
    } catch (Exception e) {
      throw new IOException(e);
    } finally {
      if (!assembleFinished.get()) {
        delete(outFile);
        delete(ffOutFile);
      }
    }
  }

  protected abstract void updateStateFinal(String fileName, String folder, long totalBytes);

  private void delete(File file) {
    if (file != null) {
      try {
        Files.deleteIfExists(file.toPath());
      } catch (Exception ex) {
        logger.debug("delete failed");
      }
    }
  }

  private void renameTo(File f1, File f2) {
    if (!f1.renameTo(f2)) {
      logger.error("Unable to rename");
    }
  }

  public void stop() {
    this.speedLimiter.wakeIfSleeping();
    stopFlag.set(true);
    saveState();
    for (int i = 0; i < chunks.size(); i++) {
      chunks.get(i).stop();
    }
    listener.downloadStopped(id);
  }

//  private void saveState() {
//    if (length.get() < 0) return;
//    StringBuilder sb = new StringBuilder();
//    sb.append(this.length + "\n");
//    sb.append(downloaded + "\n");
//    sb.append(chunks.size() + "\n");
//    for (int i = 0; i < chunks.size(); i++) {
//      Chunk seg = chunks.get(i);
//      sb.append(seg.getId() + "\n");
//      sb.append(seg.getLength() + "\n");
//      sb.append(seg.getStartOffset() + "\n");
//      sb.append(seg.getDownloaded() + "\n");
//    }
//    if (!StringUtils.isNullOrEmptyOrBlank(lastModified)) {
//      sb.append(this.lastModified + "\n");
//    }
//    try {
//      File tmp = new File(folder, System.currentTimeMillis() + ".tmp");
//      File out = new File(folder, "state.txt");
//      try (FileOutputStream fs = new FileOutputStream(tmp)) {
//        fs.write(sb.toString().getBytes());
//      }
//      delete(out);
//      renameTo(tmp, out);
//    } catch (Exception e) {
//      logger.error(e.getMessage(), e);
//    }
//  }

  private boolean restoreState() {
    chunks = new ArrayList<Chunk>();
    File file = new File(folder, "state.txt");
    if (!file.exists()) {
      file = getBackupFile(folder);
      if (file == null) {
        return false;
      }
    }
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      this.length.set(Long.parseLong(br.readLine()));
      this.downloaded.set(Long.parseLong(br.readLine()));
      int chunkCount = Integer.parseInt(br.readLine());
      for (int i = 0; i < chunkCount; i++) {
        long cid = Long.parseLong(XDMUtils.readLineSafe(br));
        long len = Long.parseLong(XDMUtils.readLineSafe(br));
        long off = Long.parseLong(XDMUtils.readLineSafe(br));
        long dwn = Long.parseLong(XDMUtils.readLineSafe(br));
        Chunk seg = new ChunkImpl(folder, cid, off, len, dwn);
        // handle case of single dash stream
        //        if (getMetadata() instanceof DashMetadata) {
        //          seg.setTag("T1");
        //        }

        logger.info(
            "id: {} length: {} offset: {} download: {}",
            seg.getId(),
            seg.getLength(),
            seg.getStartOffset(),
            seg.getDownloaded());

        chunks.add(seg);
      }
      this.lastModified = br.readLine();
      return true;
    } catch (Exception e) {
      logger.error("Failed to load saved state", e);
    }
    return false;
  }

  protected abstract void chunkConfirmed(Chunk c);

  public boolean shouldCleanup() {
    return assembleFinished.get();
  }

  public SegmentDetails getSegmentDetails() {
    return this.downloadProgressData.getSegmentDetails();
  }

  long lastUpdatePosted = 0;

  private void updateStatus(int bytes) {
    try {
      long now = System.currentTimeMillis();
      if (converting) {
        progress.set(XDMUtils.clamp(this.convertPrg, 0, 100));
      } else if (this.assembling) {
        long len = length.get() > 0 ? length.get() : downloaded.get();
        progress.set(XDMUtils.clamp((int) ((totalAssembled * 100) / len), 0, 100));
      } else {
        this.downloadProgressData.updateDownloadInfo(bytes, this.chunks);
        this.progress.set(
            XDMUtils.clamp((int) (this.downloaded.get() * 100 / this.length.get()), 0, 100));
        this.eta = downloadProgressData.getEta();
        this.downloadSpeed = downloadProgressData.getDownloadSpeed();
      }
      if (now - this.lastUpdatePosted > 1000) {
        listener.downloadUpdated(id);
        this.lastUpdatePosted = now;
      }
    } catch (Exception e) {
      logger.error(e.getMessage(), e);
    }
  }

  private void assembleAsync() {
    new Thread(
            () -> {
              finished.set(true);
              try {
                assemble();
                if (!assembleFinished.get()) {
                  throw new IOException("Assemble not finished successfully");
                }
                logger.error("********Download finished*********");
                updateStatus(0);
                cleanup();
                listener.downloadFinished(id);
              } catch (Exception e) {
                if (!stopFlag.get()) {
                  logger.error(e.getMessage(), e);
                  listener.downloadFailed(id, ErrorCode.ERR_ASM_FAILED);
                }
              }
            })
        .start();
  }

  @Override
  public synchronized void chunkFailed(long id, String reason) {
    if (stopFlag.get()) return;
    // If all chunks are inactive and return the error or if any chunk is active then return 0
    ErrorCode err = getChunkError();
    if (err == null) {
      return;
    }
    if (finished.get()) {
      return;
    }
    this.listener.downloadFailed(this.id, getFinalError(err));
    logger.error("failed");
  }

  @Override
  public synchronized boolean promptCredential(String msg, boolean proxy) {
    return credentialProvider.promptCredential(id, msg, proxy);
  }

  protected synchronized int retryFailedChunks(int rem) throws IOException {
    if (stopFlag.get()) return 0;
    int count = 0;
    int totalInactive = findTotalInactiveChunk();
    logger.info("Total inactive chunks: {}", totalInactive);

    if (totalInactive > rem) {
      totalInactive = rem;
    }
    if (totalInactive > 0) {
      for (; totalInactive > 0; totalInactive--) {
        Chunk c = findInactiveChunk();
        if (c != null) {
          c.download(this);
          count++;
        }
      }
    }
    return count;
  }

  protected Chunk findInactiveChunk() {
    if (stopFlag.get()) return null;
    for (Chunk c : chunks) {
      if (c.isFinished() || c.isActive()) continue;
      return c;
    }
    return null;
  }

  protected int findTotalInactiveChunk() {
    int count = 0;
    for (Chunk c : chunks) {
      if (c.isFinished() || c.isActive()) continue;
      count++;
    }
    return count;
  }

  public int getActiveChunkCount() {
    int count = 0;
    for (Chunk chunk : chunks) {
      if (chunk.isActive()) {
        count++;
      }
    }
    return count;
  }

  private ErrorCode getChunkError() {
    if (chunks.isEmpty()) {
      return null;
    }
    for (Chunk chunk : chunks) {
      if (chunk.isActive()) {
        return null;
      }
    }
    return chunks.get(0).getErrorCode();
  }

  private ErrorCode getFinalError(ErrorCode err) {
    ErrorCode errorCode;
    if (err == ErrorCode.ERR_INVALID_RESP) {
      if (downloaded.get() > 0) {
        if (length.get() > 0) {
          if (chunks.size() > 1) {
            errorCode = ErrorCode.ERR_SESSION_EXPIRED;
          } else {
            errorCode = ErrorCode.ERR_NO_RESUME;
          }
        } else {
          errorCode = ErrorCode.ERR_NO_RESUME;
        }
      } else {
        errorCode = ErrorCode.ERR_INVALID_RESP;
      }
    } else {
      logger.info("Setting final error code: {}", err);
      errorCode = err;
    }
    return errorCode;
  }

  protected boolean allFinished() {
    if (!chunks.isEmpty()) {
      for (Chunk chunk : chunks) {
        if (!chunk.isFinished()) {
          return false;
        }
      }
      return true;
    } else {
      return false;
    }
  }

  protected Chunk getById(long id) {
    for (Chunk chunk : chunks) {
      if (chunk.getId() == id) {
        return chunk;
      }
    }
    return null;
  }

  public void getLastModifiedDate(Chunk c) {
    if (StringUtils.isNullOrEmpty(lastModified)) {
      try {
        if (c.getChunkRetriever() instanceof HttpChunkRetriever) {
          this.lastModified =
              ((HttpChunkRetriever) c.getChunkRetriever()).getHeader("last-modified");
        }
      } catch (Exception e) {
        logger.error("Error getLastModifiedDate", e);
      }
    }
  }

  protected void clearChannel(Chunk s) {
    if (s != null) {
      s.clearChannel();
    }
  }

  public synchronized void synchronize() {}
}
