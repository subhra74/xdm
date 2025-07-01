package xdm.app.service.impl;

import xdm.app.AppContext;
import xdm.app.models.DownloadEntry;
import xdm.app.service.AppControllerService;
import xdm.app.ui.screens.AppWindow;
import xdm.app.ui.screens.NewDownloadWindow;
import xdm.app.utils.AppUtils;
import xdm.app.utils.TrayUtils;
import xdm.core.downloaders.AbstractDownloader;
import xdm.core.downloaders.http.HttpMetadata;

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
    var index = AppContext.INSTANCE.getDownloadsDbService().indexById(id);
    if (index != null) {
      SwingUtilities.invokeLater(() -> appWindow.updateDownloadInView(index));
    }
  }

  @Override
  public void updateDownloadInView(DownloadEntry entry) {}

  @Override
  public void addDownloadInView(long id) {
    var index = AppContext.INSTANCE.getDownloadsDbService().indexById(id);
    if (index != null) {
      SwingUtilities.invokeLater(() -> appWindow.addDownloadInView(index));
    }
  }

  @Override
  public void addDownloadInView(DownloadEntry entry) {}

  @Override
  public void updateProgressWindow(long id, AbstractDownloader downloader) {}

  @Override
  public void showErrorInProgressWindow(long id, String errorMessage) {}

  public void showNewDownloadWindow(final HttpMetadata metadata) {
    SwingUtilities.invokeLater(
        () -> {
          showNewDownloadWindowInternal(metadata);
        });
  }

  private void showNewDownloadWindowInternal(final HttpMetadata metadata) {
    var dlg = new NewDownloadWindow();
    dlg.showWindow(metadata);
  }
}
