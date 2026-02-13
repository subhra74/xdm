package xdm.app.service.impl;

import xdm.app.AppContext;
import xdm.app.data.DbRecord;
import xdm.app.models.BrowserDownloadInfo;
//import xdm.app.models.DownloadEntry;
import xdm.app.service.AppControllerService;
import xdm.app.ui.screens.AppWindow;
import xdm.app.ui.screens.NewDownloadWindow;
import xdm.app.ui.screens.NewVideoDownloadWindow;
import xdm.app.utils.AppUtils;
import xdm.app.utils.TrayUtils;
import xdm.core.downloaders.HttpDownloadTaskInfo;
import xdm.core.downloaders.Metadata;
import xdm.integration.BrowserIntegration;

import javax.swing.*;

public class AppControllerServiceImpl implements AppControllerService {
  private AppWindow appWindow;

  @Override
  public void run(String[] args) {
    SwingUtilities.invokeLater(
        () -> {
          var image = AppUtils.createSVGIcon("xdm-logo.svg", 256).getImage();
          appWindow = new AppWindow(image);
          TrayUtils.createTray(image);
          showAppWindow();
        });
  }

  @Override
  public void showAppWindow() {
    appWindow.setVisible(true);
    appWindow.toFront();
  }

  @Override
  public void hideDownloadProgressWindow(long id) {}

  @Override
  public void showDownloadProgressWindow(long id) {}

  @Override
  public void showDownloadCompleteWindow(long id, String folder, String fileName) {}

  @Override
  public void updateDownloadInView(long id) {
    var index = AppContext.INSTANCE.getDb().indexById(id);
    if (index != null) {
      SwingUtilities.invokeLater(() -> appWindow.updateDownloadInView(index));
    }
  }

  @Override
  public void updateDownloadInView(DbRecord entry) {}

  @Override
  public void addDownloadInView(long id) {
    var index = AppContext.INSTANCE.getDb().indexById(id);
    if (index != null) {

      SwingUtilities.invokeLater(() -> appWindow.addDownloadInView(index));
    }
  }

  @Override
  public void addDownloadInView(DbRecord entry) {}

  //  @Override
  //  public void updateProgressWindow(long id, AbstractDownloader downloader) {}

  @Override
  public void showErrorInProgressWindow(long id, String errorMessage) {}

  public void addDownload(final HttpDownloadTaskInfo downloadInfo) {
    // TODO: Check if link refresh is searching for download
    // TODO: Check if download window needs to be shown, or directly start the download
    SwingUtilities.invokeLater(
        () -> {
          showNewDownloadWindowInternal(downloadInfo);
        });
  }

  public void addVideoDownload(long vid, String fileName, long fileSize, String contentType) {
    // TODO: Check if download window needs to be shown, or directly start the download
    SwingUtilities.invokeLater(
        () -> {
          showNewVideoDownloadWindowInternal(vid, fileName, fileSize, contentType);
        });
  }

  private void showNewDownloadWindowInternal(final HttpDownloadTaskInfo downloadInfo) {
    var dlg = new NewDownloadWindow();
    dlg.showWindow(downloadInfo);
  }

  private void showNewVideoDownloadWindowInternal(
      long vid, String fileName, long fileSize, String contentType) {
        var dlg = new NewVideoDownloadWindow();
        dlg.showWindow(vid, fileName, fileSize, contentType);
  }
}
