package xdm;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.app.ui.screens.MainWindow;

import javax.swing.*;
import java.awt.*;

public class Main {
  private static Logger logger = LoggerFactory.getLogger(Main.class);

  static {
    System.setProperty("http.KeepAlive.remainingData", "0");
    System.setProperty("http.KeepAlive.queuedConnections", "0");
    System.setProperty("sun.net.http.errorstream.enableBuffering", "false");
    System.setProperty("awt.useSystemAAFontSettings", "lcd");
    System.setProperty("swing.aatext", "true");
    System.setProperty("sun.java2d.d3d", "false");
    System.setProperty("sun.java2d.opengl", "false");
    System.setProperty("sun.java2d.xrender", "false");
    // Disable Java 9 Dpi scaling as XDM uses its own dpi scaling
    // System.setProperty("sun.java2d.uiScale.enabled", "true");
    // System.setProperty("sun.java2d.uiScale", "2.75");
  }

  public static void main(String[] args) {
    System.setProperty("apple.awt.application.appearance", "system");
    FlatMacDarkLaf.setup();
    UIManager.put("TableHeader.cellMargins", new Insets(0, 10, 0, 0));
//    UIManager.put("Component.focusWidth", 0);
    logger.info("loading...");
    logger.info(
        "Java version: {} - OS: {} {}",
        System.getProperty("java.version"),
        System.getProperty("os.name"),
        System.getProperty("os.version"));
//    SwingUtilities.invokeLater(
//        () -> {
//          new MainWindow().setVisible(true);
//        });
    App.start(args);
  }
}
