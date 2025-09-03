package xdm.app.service;

import xdm.core.downloaders.Metadata;

import java.util.List;

public interface DownloadsControllerService {
  void startDownload(Metadata metadata, boolean startNow, long queueId);

  void pauseDownload(long id);

  void resumeDownload(long id, boolean nonInteractive);

  void restartDownload(long id);

  void deleteDownload(List<Long> id);
}
