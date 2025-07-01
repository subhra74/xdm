package xdm.app;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import xdm.app.service.impl.*;
import xdman.XDMApp;
import xdman.util.Logger;

import javax.swing.*;
import java.awt.*;

/** Hello world! */
public class AppMain {
  static {
    System.setProperty("http.KeepAlive.remainingData", "0");
    System.setProperty("http.KeepAlive.queuedConnections", "0");
    System.setProperty("awt.useSystemAAFontSettings", "lcd");
    System.setProperty("swing.aatext", "true");
    System.setProperty("sun.java2d.d3d", "false");
    System.setProperty("sun.java2d.opengl", "false");
    System.setProperty("sun.java2d.xrender", "false");
  }

  public static void main(String[] args) {
    Logger.log("loading...");
    Logger.log(System.getProperty("java.version") + " " + System.getProperty("os.version"));

    System.setProperty("apple.awt.application.appearance", "system");
    System.setProperty("apple.laf.useScreenMenuBar", "true");
    System.setProperty("apple.awt.application.name", "XDM");

    FlatMacDarkLaf.setup();
    UIManager.put("TableHeader.cellMargins", new Insets(0, 10, 0, 0));
    UIManager.put("SplitPaneDivider.gripDotCount", 0);
    UIManager.put("SplitPane.dividerSize", 10);

    AppContext.INSTANCE.setDownloadsDbService(new DownloadsDBServiceImpl());
    AppContext.INSTANCE.setAppControllerService(new AppControllerServiceImpl());
    AppContext.INSTANCE.setConfigService(new AppConfigServiceImpl());
    AppContext.INSTANCE.setQueueService(new QueueServiceImpl());
    AppContext.INSTANCE.setPlatformService(new PlatformServiceImpl());
    AppContext.INSTANCE.setDownloadsControllerService(new DownloadsControllerServiceImpl());
    AppContext.INSTANCE.setRpcService(new RpcServiceImpl());

    AppContext.INSTANCE.start(args);

    //    UIManager.put("Tree.selectionArc", 10);
    //    UIManager.put("ScrollPane.Tree.arc", 10);
        //UIManager.put("Tree.leftChildIndent", 15);
//        UIManager.put("Tree.rightChildIndent", 10);

    //    XDMApp.start(args);
  }
}
