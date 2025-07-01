package xdm.core.downloaders;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.Config;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.util.HttpDateParser;

public abstract class AbstractDownloader {
  private final Logger logger = LoggerFactory.getLogger(AbstractDownloader.class);
  @Getter protected final DownloaderType downloaderType;
  protected AtomicBoolean stopFlag = new AtomicBoolean(false);
  protected AtomicLong length = new AtomicLong();
  protected String folder;
  @Getter protected final long id;
  protected AtomicBoolean finished = new AtomicBoolean(false);
  @Getter protected int maxCount = 8;
  protected final DownloadProgressListener listener;
  protected SpeedLimiter speedLimiter;
  protected AtomicLong downloaded = new AtomicLong(0);
  @Getter protected AtomicInteger progress = new AtomicInteger(0);
  protected long lastUpdated;
  protected long lastSaved;
  @Getter protected boolean assembling;
  @Getter protected float downloadSpeed;
  @Getter protected long eta;
  protected int outputFormat;
  @Getter protected boolean converting;
  protected int convertPrg;
  protected String lastModified;
  protected InteractiveCredentialProvider credentialProvider;

  public abstract void start();

  public abstract void stop();

  public abstract void resume();

  public abstract int getType();

  public long getSize() {
    return length.get();
  }

  public long getDownloaded() {
    return downloaded.get();
  }

  public abstract Metadata getMetadata();

  public abstract SegmentDetails getSegmentDetails();

  public void setOutputMediaFormat(int format) {
    this.outputFormat = format;
  }

  protected AbstractDownloader(
      long id,
      DownloaderType downloaderType,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    this.id = id;
    this.downloaderType = downloaderType;
    this.speedLimiter = new SpeedLimiter(this);
    this.listener = listener;
    this.credentialProvider = credentialProvider;
  }

  public synchronized void cleanup() {
    logger.info("Cleaning up temp files");
    try {
      Path folderPath = Paths.get(folder);
      if (!Files.exists(folderPath)) {
        return;
      }
      try (Stream<Path> files = Files.walk(folderPath).sorted(Comparator.reverseOrder())) {
        files.forEach(
            f -> {
              try {
                Files.delete(f);
                logger.info("Successfully delete file: {}", f);
              } catch (IOException e) {
                logger.error("Error deleting temp file: {}", f, e);
              }
            });
      }
    } catch (Exception ex) {
      logger.error("Error cleaning up temp files", ex);
    }
  }

  protected String getOutputFolder() {
    return listener.getOutputFolder(id);
  }

  protected File getBackupFile(String folder) {
    File f = new File(folder);
    File[] files = f.listFiles();
    if (files == null || files.length < 1) return null;
    for (File file : files) {
      if (file.getName().endsWith(".bak")) {
        return file;
      }
    }
    return null;
  }

  public void setLastModifiedDate(File outFile) {
    if (Config.getInstance().isFetchTs()) {
      try {
        Date date = HttpDateParser.parseHttpDate(this.lastModified);
        if (date != null) {
          boolean val = outFile.setLastModified(date.getTime());
          logger.info("Updated last modified: {}", val);
        }
      } catch (Exception e) {
        logger.error("Error setLastModifiedDate", e);
      }
    }
  }

  public void throttle() {
    this.speedLimiter.throttleIfNeeded();
  }

  protected String getOutputFileName() {
    return listener.getOutputFileName(id);
  }
}
