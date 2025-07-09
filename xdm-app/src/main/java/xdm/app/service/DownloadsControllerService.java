package xdm.app.service;

import xdm.core.downloaders.Metadata;

public interface DownloadsControllerService {
  void startDownload(Metadata metadata, boolean startNow, long queueId);

  void pauseDownload(long id);

  void resumeDownload(long id, boolean nonInteractive);

  void restartDownload(long id);
}
