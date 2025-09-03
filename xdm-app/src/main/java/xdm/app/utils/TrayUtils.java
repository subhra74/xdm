package xdm.app.utils;

import xdm.app.AppContext;
import xdman.util.Logger;

import java.awt.*;

public final class TrayUtils {
  private static final Logger logger = Logger.getLogger(TrayUtils.class);

  public static void createTray(Image image) {
    if (!SystemTray.isSupported()) {
      logger.info("SystemTray is not supported");
      return;
    }

    // final var image = AppUtils.createSVGIcon("xdm-logo.svg", 32);
    final TrayIcon trayIcon = new TrayIcon(image);
    trayIcon.setImageAutoSize(true);
    trayIcon.addActionListener(e -> AppContext.INSTANCE.getApp().showAppWindow());
    final SystemTray tray = SystemTray.getSystemTray();
    try {
      tray.add(trayIcon);
    } catch (Exception ex) {
      logger.error(ex.getMessage(), ex);
    }
  }
}
