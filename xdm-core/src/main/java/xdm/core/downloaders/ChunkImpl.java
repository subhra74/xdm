//package xdm.core.downloaders;
//
//import java.io.File;
//import java.io.IOException;
//import java.io.RandomAccessFile;
//import java.util.concurrent.atomic.AtomicBoolean;
//import java.util.concurrent.atomic.AtomicLong;
//
//import lombok.Getter;
//import lombok.Setter;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import xdm.core.constants.ErrorCode;
//import xdm.core.util.UniqueID;
//
//public class ChunkImpl implements Chunk {
//  private static final Logger logger = LoggerFactory.getLogger(ChunkImpl.class);
//  private AtomicLong length = new AtomicLong();
//  private AtomicLong startOffset = new AtomicLong();
//  private AtomicLong downloaded = new AtomicLong();
//  private RandomAccessFile outStream;
//  private long id;
//  private ChunkUpdateListener cl;
//  private AbstractChunkRetriever chunkRetriever;
//  private final AtomicBoolean stop = new AtomicBoolean(false);
//  private ErrorCode errorCode;
//  private Object tag;
//  private final String folder;
//  @Getter @Setter private long lastTakeOverTime;
//
//  public ChunkImpl(ChunkUpdateListener cl, String folder) {
//    id = UniqueID.get();
//    this.cl = cl;
//    this.folder = folder;
//    logger.info("Chunk created: {}", id);
//  }
//
//  public ChunkImpl(String folder, long id, long off, long len, long dwn) {
//    this.id = id;
//    this.startOffset = new AtomicLong(off);
//    this.folder = folder;
//    this.length = new AtomicLong(len);
//    this.downloaded = new AtomicLong(dwn);
//  }
//
//  @Override
//  public long getLength() {
//    return length.get();
//  }
//
//  @Override
//  public long getStartOffset() {
//    return startOffset.get();
//  }
//
//  @Override
//  public long getDownloaded() {
//    return downloaded.get();
//  }
//
//  @Override
//  public RandomAccessFile getOutStream() {
//    return outStream;
//  }
//
//  @Override
//  public boolean transferComplete() throws IOException {
//    if (stop.get()) return true;
//    if (length.get() < 0) {
//      length.set(downloaded.get());
//    }
//    try {
//      outStream.close();
//      outStream = null;
//    } catch (IOException e) {
//      // Swallow exception
//    }
//    if (cl.chunkComplete(id)) {
//      chunkRetriever = null;
//      try {
//        if (cl.shouldCleanup()) {
//          cl.cleanup();
//        }
//      } catch (Exception ex) {
//        // Ignore error
//      }
//      return true;
//    } else {
//      reopenStream();
//      return false;
//    }
//  }
//
//  public void clearChannel() {
//    this.chunkRetriever = null;
//  }
//
//  @Override
//  public void transferInitiated() throws IOException {
//    if (stop.get()) return;
//    cl.chunkInitiated(id);
//  }
//
//  @Override
//  public void transferFailed(String reason) {
//    if (stop.get()) return;
//    if (outStream != null) {
//      try {
//        outStream.close();
//        outStream = null;
//      } catch (IOException e) {
//        // Swallow error
//      }
//    }
//    this.errorCode = chunkRetriever.getErrorCode();
//    logger.info("{} notifying failure {}", id, this.chunkRetriever);
//    this.chunkRetriever = null;
//    cl.chunkFailed(id, reason);
//    cl = null;
//  }
//
//  @Override
//  public boolean isFinished() {
//    return (getLength() - getDownloaded()) == 0;
//  }
//
//  @Override
//  public boolean isActive() {
//    return this.chunkRetriever != null;
//  }
//
//  @Override
//  public long getId() {
//    return id;
//  }
//
//  @Override
//  public void download(ChunkUpdateListener cl) {
//    this.cl = cl;
//    chunkRetriever = cl.createChannel(this);
//    chunkRetriever.open();
//  }
//
//  @Override
//  public void setLength(long length) {
//    this.length.set(length);
//  }
//
//  @Override
//  public void setDownloaded(long downloaded) {
//    this.downloaded.set(downloaded);
//  }
//
//  @Override
//  public void setStartOffset(long offset) {
//    this.startOffset.set(offset);
//  }
//
//  @Override
//  public void stop() {
//    stop.set(true);
//    dispose();
//  }
//
//  @Override
//  public ChunkUpdateListener getChunkListener() {
//    return cl;
//  }
//
//  @Override
//  public void dispose() {
//    cl = null;
//    logger.info("Stopping chunkRetriever: {}", this.id);
//    if (chunkRetriever != null) {
//      chunkRetriever.stop();
//    }
//    if (outStream != null) {
//      try {
//        outStream.close();
//        outStream = null;
//      } catch (IOException e) {
//        // Ignore error
//      }
//    }
//  }
//
//  @Override
//  public String toString() {
//    return String.valueOf(id);
//  }
//
//  @Override
//  public void transferring(int bytes) {
//    if (stop.get()) return;
//    cl.chunkUpdated(id, bytes);
//  }
//
//  @Override
//  public AbstractChunkRetriever getChunkRetriever() {
//    return chunkRetriever;
//  }
//
//  @Override
//  public void setId(long id) {
//    this.id = id;
//  }
//
//  @Override
//  public ErrorCode getErrorCode() {
//    return this.errorCode;
//  }
//
//  @Override
//  public Object getTag() {
//    return tag;
//  }
//
//  public void setTag(Object tag) {
//    this.tag = tag;
//  }
//
//  public String getErrorMsg() {
//    return null;
//  }
//
//  @Override
//  public void resetStream() throws IOException {
//    outStream.seek(0);
//    outStream.setLength(0);
//  }
//
//  public void reopenStream() throws IOException {
//    if (outStream != null) {
//      logger.error("Reopen called on active stream!");
//      return;
//    }
//    try {
//      outStream = new RandomAccessFile(new File(folder, String.valueOf(id)), "rw");
//      outStream.seek(downloaded.get());
//      logger.info("File opened {}", id);
//    } catch (IOException e) {
//      if (outStream != null) {
//        outStream.close();
//        outStream = null;
//      }
//      throw new IOException(e);
//    }
//  }
//
//  @Override
//  public boolean promptCredential(String msg, boolean proxy) {
//    return cl.promptCredential(msg, proxy);
//  }
//}
