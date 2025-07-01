package xdm.app.service;

import xdm.core.downloaders.Metadata;

public interface DownloadsControllerService {
  void startDownload(Metadata metadata, boolean startNow, long queueId);
}
