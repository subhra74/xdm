package xdm.app.service;

import xdm.app.data.DbRecord;
import xdm.app.models.BrowserDownloadInfo;

public interface AppControllerService {
  void run(String[] args);

  void showAppWindow();

  void hideDownloadProgressWindow(long id);

  void showDownloadProgressWindow(long id);

  void showDownloadCompleteWindow(long id, String folder, String fileName);

  void updateDownloadInView(long id);

  void updateDownloadInView(DbRecord entry);

  void addDownloadInView(long id);

  void addDownloadInView(DbRecord entry);

  //  void updateProgressWindow(long id, AbstractDownloader downloader);

  void showErrorInProgressWindow(long id, String errorMessage);

  void addDownload(final BrowserDownloadInfo metadata);

  void addVideoDownload(long vid, String fileName, long fileSize, String fileType);
}
