package xdm.app.service.impl;

import xdm.app.AppContext;
import xdm.app.constants.DownloadEntryState;
import xdm.app.misc.SleepBlocker;
import xdm.app.models.DownloadEntry;
import xdm.app.models.DownloaderHolder;
import xdm.app.service.DownloadsControllerService;
import xdm.core.DownloadProgressListener;
import xdm.core.constants.ErrorCode;
import xdm.core.downloaders.AbstractDownloader;
import xdm.core.downloaders.Metadata;
import xdm.core.downloaders.hls.HlsDownloader;
import xdm.core.downloaders.hls.HlsSource;
import xdm.core.downloaders.http.HttpDownloader;
import xdm.core.downloaders.http.HttpSource;
import xdm.core.util.FileUtils;
import xdm.core.util.MetadataStore;
import xdman.ui.res.StringResource;
import xdman.util.Logger;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DownloadsControllerServiceImpl implements DownloadsControllerService {
  private static final Logger logger = Logger.getLogger(DownloadsControllerServiceImpl.class);
  private final Map<Long, DownloaderHolder> activeDownloads = new ConcurrentHashMap<>();
  private final Map<Long, Boolean> pendingDownloads =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final DownloadProgressListener listener;
  private final SleepBlocker sleepBlocker = new SleepBlocker();

  public DownloadsControllerServiceImpl() {
    this.listener =
        new DownloadProgressListener() {
          @Override
          public void downloadFinished(long id) {
            logger.info("Download finished...");
            onDownloadFinished(id);
          }

          @Override
          public void downloadFailed(long id, ErrorCode errorCode) {
            onDownloadFailed(id, errorCode);
          }

          @Override
          public void downloadStopped(long id) {
            onDownloadStopped(id);
          }

          @Override
          public void downloadConfirmed(long id) {
            onDownloadConfirmed(id);
          }

          @Override
          public void downloadUpdated(long id) {
            onDownloadUpdate(id);
          }

          @Override
          public String getOutputFolder(long id) {
            return getDownloadFolder(id);
          }

          @Override
          public String getOutputFileName(long id) {
            return getTargetFileName(id);
          }
        };
  }

  public void startDownload(Metadata metadata, boolean startNow, long queueId) {
    MetadataStore.save(metadata);
    var id = metadata.getId();
    final AbstractDownloader downloader = createDownloader(metadata);
    if (downloader == null) {
      return;
    }
    if (queueId != -1) {
      AppContext.INSTANCE.getQueue().attachToQueue(queueId, List.of(id));
    }
    var ent =
        DownloadEntry.builder()
            .id(id)
            .state(startNow ? DownloadEntryState.DOWNLOADING : DownloadEntryState.PAUSED)
            .fileName(metadata.getFileName())
            .dateEpoch(System.currentTimeMillis())
            .build();
    if (startNow
        && activeDownloads.size() >= AppContext.INSTANCE.getConfig().getMaxParallelDownloads()) {
      startNow = false;
      pendingDownloads.put(id, false);
    }

    AppContext.INSTANCE.getDb().add(ent);
    AppContext.INSTANCE.getDb().save();
    AppContext.INSTANCE.getApp().addDownloadInView(id);

    if (startNow) {
      this.activeDownloads.put(
          id, DownloaderHolder.builder().downloader(downloader).isNonInteractive(false).build());
      if (AppContext.INSTANCE.getConfig().shouldShowDownloadProgressWindow()) {
        AppContext.INSTANCE.getApp().showDownloadProgressWindow(id);
      }
      downloader.start();
    }
  }

  @Override
  public void pauseDownload(long id) {
    new Thread(() -> stopDownloads(List.of(id))).start();
  }

  @Override
  public void resumeDownload(long id, boolean nonInteractive) {
    new Thread(() -> resumeDownload(List.of(id), nonInteractive)).start();
  }

  @Override
  public void restartDownload(long id) {}

  @Override
  public void deleteDownload(List<Long> id) {}

  private synchronized void onDownloadFinished(long id) {
    try {
      var value = activeDownloads.remove(id);
      if (value == null) {
        return;
      }
      var nonInteractive = value.isNonInteractive();
      var downloader = value.getDownloader();

      AppContext.INSTANCE.getApp().hideDownloadProgressWindow(id);

      var ent = AppContext.INSTANCE.getDb().getById(id);
      ent.setState(DownloadEntryState.FINISHED);
      if (downloader != null && downloader.getSize() < 0) {
        ent.setSize(downloader.getDownloaded());
        ent.setFileName(downloader.getMetadata().getFileName());
      }
      AppContext.INSTANCE.getDb().save();

      AppContext.INSTANCE.getApp().updateDownloadInView(id);

      var metadata = MetadataStore.get(id);
      if (metadata == null) {
        return;
      }

      var finalFolder = AppContext.INSTANCE.getConfig().getFolderForDownload(metadata);
      var finalFileName = metadata.getFileName();
      var finalFilePath = new File(finalFolder, finalFileName).getAbsolutePath();

      if (Boolean.FALSE.equals(nonInteractive)
          && AppContext.INSTANCE.getConfig().shouldShowDownloadCompleteWindow()) {
        AppContext.INSTANCE.getApp().showDownloadCompleteWindow(id, finalFolder, finalFileName);
      }
      if (AppContext.INSTANCE.getConfig().shouldRunVirusScan()) {
        AppContext.INSTANCE.getPlatform().runVirusScan(finalFilePath);
      }
      if (AppContext.INSTANCE.getConfig().shouldRunCommand()) {
        AppContext.INSTANCE.getPlatform().runCustomCommand(finalFilePath);
      }
      if (isInactive() && AppContext.INSTANCE.getConfig().shouldShutdownAfterAllDone()) {
        AppContext.INSTANCE.getPlatform().shutdownPC();
      }
    } finally {
      processNextDownload();
    }
  }

  private synchronized void onDownloadFailed(long id, ErrorCode errorCode) {
    activeDownloads.remove(id);
    var ent = AppContext.INSTANCE.getDb().getById(id);
    ent.setState(DownloadEntryState.ERROR);
    AppContext.INSTANCE.getDb().save();
    AppContext.INSTANCE.getApp().updateDownloadInView(id);
    AppContext.INSTANCE
        .getApp()
        .showErrorInProgressWindow(id, StringResource.get("ERR_MSG_" + errorCode));
    processNextDownload();
  }

  private synchronized void onDownloadStopped(long id) {
    activeDownloads.remove(id);
    var ent = AppContext.INSTANCE.getDb().getById(id);
    ent.setState(DownloadEntryState.PAUSED);
    AppContext.INSTANCE.getDb().save();
    AppContext.INSTANCE.getApp().updateDownloadInView(id);
    AppContext.INSTANCE.getApp().hideDownloadProgressWindow(id);
    processNextDownload();
  }

  private void onDownloadUpdate(long id) {
    var downloader = activeDownloads.get(id);
    if (downloader != null) {
      var ent = AppContext.INSTANCE.getDb().getById(id);
      ent.setDownloaded(downloader.getDownloader().getDownloaded());
      ent.setProgress(downloader.getDownloader().getProgress().get());
      ent.setSpeed(downloader.getDownloader().getDownloadSpeed());
      ent.setEta(downloader.getDownloader().getEta());
      AppContext.INSTANCE.getApp().updateDownloadInView(id);
      AppContext.INSTANCE.getApp().updateProgressWindow(id, downloader.getDownloader());
    }
  }

  private void onDownloadConfirmed(long id) {
    var downloader = activeDownloads.get(id);
    if (downloader != null) {
      var ent = AppContext.INSTANCE.getDb().getById(id);
      ent.setFileName(downloader.getDownloader().getMetadata().getFileName());
      ent.setSize(downloader.getDownloader().getSize());
      AppContext.INSTANCE.getDb().save();
      AppContext.INSTANCE.getApp().updateDownloadInView(id);
      AppContext.INSTANCE.getApp().updateProgressWindow(id, downloader.getDownloader());
    }
  }

  private String getDownloadFolder(long id) {
    var downloader = activeDownloads.get(id);
    if (downloader == null) {
      logger.info("Downloader expected, found none");
      return AppContext.INSTANCE.getConfig().getDefaultDownloadFolder();
    }
    return downloader.getDownloader().getMetadata().isAutoSelectFolder()
        ? AppContext.INSTANCE
            .getConfig()
            .getFolderForDownload(downloader.getDownloader().getMetadata())
        : downloader.getDownloader().getMetadata().getFolder();
  }

  private String getTargetFileName(long id) {
    var downloader = activeDownloads.get(id);
    if (downloader == null) {
      logger.info("Downloader expected, found none");
      return "File";
    }
    var folder = getDownloadFolder(id);
    if (AppContext.INSTANCE.getConfig().shouldAutoRenameOnConflict()) {
      return FileUtils.getUniqueFileName(
          folder, downloader.getDownloader().getMetadata().getFileName());
    }
    return downloader.getDownloader().getMetadata().getFileName();
  }

  private synchronized void processNextDownload() {
    if (!pendingDownloads.isEmpty()) {
      var nextItem = pendingDownloads.entrySet().iterator().next();
      pendingDownloads.remove(nextItem.getKey());
      resumeDownload(List.of(nextItem.getKey()), nextItem.getValue());
      return;
    }
    if (isInactive() && sleepBlocker.isActive()) {
      sleepBlocker.stop();
    }
  }

  public void resumeDownload(List<Long> idList, boolean nonInteractive) {
    if (!sleepBlocker.isActive()) {
      sleepBlocker.start();
    }
    for (var id : idList) {
      var ent = AppContext.INSTANCE.getDb().getById(id);
      if (ent == null || activeDownloads.containsKey(id) || pendingDownloads.containsKey(id)) {
        continue;
      }
      if (activeDownloads.size() > AppContext.INSTANCE.getConfig().getMaxParallelDownloads()) {
        ent.setState(DownloadEntryState.READY);
        pendingDownloads.put(id, nonInteractive);
        AppContext.INSTANCE.getApp().updateDownloadInView(id);
        continue;
      }
      var metadata = MetadataStore.get(id);
      if (metadata == null) {
        logger.info("Metadata not found");
        ent.setState(DownloadEntryState.ERROR);
        AppContext.INSTANCE.getApp().updateDownloadInView(id);
        continue;
      }
      final var downloader = createDownloader(metadata);
      if (downloader == null) {
        continue;
      }
      activeDownloads.put(
          id,
          DownloaderHolder.builder()
              .downloader(downloader)
              .isNonInteractive(nonInteractive)
              .build());
      if (AppContext.INSTANCE.getConfig().shouldShowDownloadProgressWindow() && !nonInteractive) {
        AppContext.INSTANCE.getApp().showDownloadProgressWindow(id);
        downloader.resume();
      }
    }
  }

  public void stopDownloads(List<Long> idList) {
    var count = 0;
    for (var id : idList) {
      var downloader = activeDownloads.remove(id);
      if (downloader != null) {
        downloader.getDownloader().stop();
      } else {
        if (pendingDownloads.remove(id) != null) {
          count++;
        }
        var ent = AppContext.INSTANCE.getDb().getById(id);
        if (ent != null) {
          ent.setState(DownloadEntryState.PAUSED);
          AppContext.INSTANCE.getApp().updateDownloadInView(id);
          AppContext.INSTANCE.getApp().hideDownloadProgressWindow(id);
        }
      }
    }
    if (count > 0) {
      processNextDownload();
    }
  }

  private AbstractDownloader createDownloader(Metadata metadata) {
    if (metadata instanceof HttpSource httpSource) {
      return new HttpDownloader(
          metadata.getId(),
          AppContext.INSTANCE.getConfig().getTempFolder(),
          httpSource,
          this.listener,
          null);
    }
    if (metadata instanceof HlsSource hlsSource) {
      return new HlsDownloader(
          metadata.getId(),
          AppContext.INSTANCE.getConfig().getTempFolder(),
          hlsSource,
          this.listener,
          null);
    }
    logger.info("Invalid metadata");
    return null;
  }

  private boolean isInactive() {
    return activeDownloads.isEmpty() && pendingDownloads.isEmpty();
  }
}
