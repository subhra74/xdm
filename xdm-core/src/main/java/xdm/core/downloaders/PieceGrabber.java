package xdm.core.downloaders;

import static java.util.AbstractMap.SimpleEntry;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.network.http.HttpResponse;
import xdm.core.network.http.PoolingHttpClient;
import xdm.core.network.http.Range;

public class PieceGrabber implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(PieceGrabber.class);
  private final DownloadablePiece piece;
  private final PoolingHttpClient httpClient;
  private final AtomicBoolean stopFlag;
  private final Metadata metadata;
  private final String tempDir;
  private final Consumer<Long> downloadCallback;
  private final Consumer<DownloadablePiece> completionCallback;
  private AutoCloseable fileHandle;

  public PieceGrabber(
      DownloadablePiece piece,
      PoolingHttpClient client,
      AtomicBoolean stopFlag,
      Metadata details,
      String tempDir,
      Consumer<Long> downloadCallback,
      Consumer<DownloadablePiece> completionCallback) {
    this.piece = piece;
    this.httpClient = client;
    this.stopFlag = stopFlag;
    this.metadata = details;
    this.tempDir = tempDir;
    this.downloadCallback = downloadCallback;
    this.completionCallback = completionCallback;
  }

  @Override
  public void run() {
    byte[] buffer = new byte[256 * 1024];
    try {
      while (!stopFlag.get()) {
        SimpleEntry<Long, Long> range = piece.getByteRange();
        Range realRange;
        if (range != null && !range.getKey().equals(range.getValue()) && range.getValue() != 0) {
          realRange =
              Range.builder()
                  .start(range.getKey() + this.piece.getDownloaded())
                  .end(range.getValue())
                  .build();
        } else {
          realRange = Range.builder().start(this.piece.getDownloaded()).build();
        }
        logger.info("Connecting to: {}", piece.getUrl());
        try (HttpResponse response =
            this.httpClient.get(
                piece.getUrl().toString(),
                this.metadata.getHeaders(),
                this.metadata.getCookies(),
                realRange)) {
          if (stopFlag.get()) return;
          int code = response.getStatusCode();
          if (realRange != null && code != 206) {
            logger.error("Chunk download failed - serve does not support resume: {}", code);
            this.piece.setStatus(PieceStatus.Error);
            return;
          }
          if (code != 206 && code != 200) {
            logger.error("Chunk download failed - invalid response: {}", code);
            this.piece.setStatus(PieceStatus.Error);
            return;
          }
          this.piece.setLength(response.getContentLength());
          this.piece.setContentType(response.getContentType());
          logger.info("Connected to: {}", piece.getUrl());
          try (RandomAccessFile raf =
                  new RandomAccessFile(new File(tempDir, this.piece.getId()), "rw");
              InputStream in = response.getInputStream()) {
            this.fileHandle = raf;
            raf.seek(this.piece.getDownloaded());
            while (!stopFlag.get()) {
              int x = in.read(buffer);
              if (x == -1) {
                this.piece.setStatus(PieceStatus.Done);
                logger.info("Piece download complete, {}", piece.getUrl());
                return;
              }
              raf.write(buffer, 0, x);
              this.piece.setDownloaded(this.piece.getDownloaded() + x);
              this.downloadCallback.accept((long) x);
            }
          }
        } catch (IOException ex) {
          logger.error("Error downloading chunk", ex);
          Thread.sleep(3000);
        }
      }
    } catch (InterruptedException ex) {
      logger.error("Thread interrupted", ex);
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      logger.error("Error downloading manifest", ex);
    } finally {
      this.fileHandle = null;
      this.completionCallback.accept(this.piece);
    }
  }

  public void stop() {
    try {
      if (this.fileHandle != null) {
        this.fileHandle.close();
      }
    } catch (Exception ex) {
      // Do nothing
    }
  }
}
