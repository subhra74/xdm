package xdm.app.service;

import xdm.app.models.DownloadEntry;
import xdm.core.downloaders.AbstractDownloader;
import xdm.core.downloaders.http.HttpMetadata;

public interface AppControllerService {
  void run(String[] args);

  void showAppWindow();

  void hideDownloadProgressWindow(long id);

  void showDownloadProgressWindow(long id);

  void showDownloadCompleteWindow(long id, String folder, String fileName);

  void updateDownloadInView(long id);

  void updateDownloadInView(DownloadEntry entry);

  void addDownloadInView(long id);

  void addDownloadInView(DownloadEntry entry);

  void updateProgressWindow(long id, AbstractDownloader downloader);

  void showErrorInProgressWindow(long id, String errorMessage);

  void showNewDownloadWindow(final HttpMetadata metadata);
}
