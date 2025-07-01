package xdm.core.downloaders;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.constants.ErrorCode;

public abstract class AbstractChunkRetriever implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(AbstractChunkRetriever.class);
  protected Chunk chunk;
  private InputStream in;
  protected AtomicBoolean stop = new AtomicBoolean(false);
  protected String errorMessage;
  private boolean closed;
  private Thread t;
  @Getter protected ErrorCode errorCode;
  private final long id;

  protected AbstractChunkRetriever(Chunk chunk) {
    this.chunk = chunk;
    this.id = chunk.getId();
  }

  public void open() {
    t = new Thread(this);
    t.setName(String.format("Chunk: %d", this.chunk.getId()));
    t.start();
  }

  protected abstract boolean connectImpl();

  protected abstract InputStream getInputStreamImpl();

  protected abstract long getLengthImpl();

  protected abstract void closeImpl();

  private boolean connect() {
    try {
      chunk.getChunkListener().synchronize();
    } catch (NullPointerException e) {
      logger.info("{} - stopped chunk: {}", id, chunk);
      return false;
    }
    if (connectImpl()) {
      in = getInputStreamImpl();
      long length = getLengthImpl();
      if (chunk.getLength() < 0) {
        logger.info("{} - Setting length to: {}", id, length);
        chunk.setLength(length);
      }
      return true;
    } else {
      return false;
    }
  }

  @Override
  public void run() {
    try {
      logger.info("[{}] thread start", id);
      byte[] buf = new byte[256 * 1024];
      while (!stop.get()) {
        if (!connect()) {
          if (!stop.get()) {
            chunk.transferFailed(errorMessage);
          }
          return;
        }

        // do not proceed if chunk is stopped
        if (chunk == null) {
          continue;
        }

        chunk.transferInitiated();
        if (copyData(buf)) {
          logger.info("{} - Copy Stream finished", id);
          return;
        } else {
          logger.info("{} - Copy Stream not finished", id);
        }
      }
    } catch (Exception e) {
      logger.error("{} Internal problem", id, e);
      if (!stop.get() && chunk != null) {
        chunk.transferFailed(errorMessage);
      }
    } finally {
      close();
      logger.info("[{}] thread stop", id);
    }
  }

  private boolean copyData(byte[] buf) {
    return ((chunk.getLength() > 0)
        ? copyStreamWithFixedLength(buf)
        : copyStreamWithUnknownLength(buf));
  }

  private void close() {
    if (closed) return;
    closeImpl();
    closed = true;
  }

  public void stop() {
    stop.set(true);
    this.chunk = null;
    if (this.t != null) {
      t.interrupt();
    }
  }

  private boolean copyStreamWithFixedLength(final byte[] buf) {
    logger.info("{} - Receiving by copyStream1", id);
    try {
      while (!stop.get()) {
        chunk.getChunkListener().synchronize();
        long rem = chunk.getLength() - chunk.getDownloaded();
        if (rem == 0) {
          if (chunk.transferComplete()) {
            logger.info(
                "{} - complete and closing {} {}", id, chunk.getDownloaded(), chunk.getLength());
            close();
            return true;
          }
        }
        if (stop.get()) {
          return false;
        }

        int diff = (int) Math.min(rem, buf.length);

        int x = in.read(buf, 0, diff);
        if (stop.get()) return false;
        if (x == -1) {
          logger.error("{} - Unexpected eof", id);
          throw new IOException(
              "Unexpected eof - downloaded: "
                  + chunk.getDownloaded()
                  + " expected: "
                  + chunk.getLength());
        }
        chunk.getOutStream().write(buf, 0, x);
        if (stop.get()) return false;
        chunk.setDownloaded(chunk.getDownloaded() + x);
        chunk.transferring(x);
      }
      return false;
    } catch (Exception e) {
      logger.error("{} - error", id, e);
      return false;
    } finally {
      close();
    }
  }

  private boolean copyStreamWithUnknownLength(final byte[] buf) {
    logger.info("{} - Receiving by copyStream2", id);
    try {
      while (!stop.get()) {
        chunk.getChunkListener().synchronize();
        int x = in.read(buf, 0, buf.length);
        if (stop.get()) return false;
        if (x == -1) {
          chunk.transferComplete();
          return true;
        }
        chunk.getOutStream().write(buf, 0, x);
        if (stop.get()) return false;
        chunk.setDownloaded(chunk.getDownloaded() + x);
        chunk.transferring(x);
      }
      return false;
    } catch (Exception e) {
      logger.error("{} - error", id, e);
      return false;
    } finally {
      close();
    }
  }
}
