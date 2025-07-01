package xdm.app.util;

import xdm.App;
import xdm.core.Config;
import xdm.core.XDMConstants;
import xdm.core.util.Logger;
import xdm.core.util.XDMUtils;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Locale;

public class PlatformUtils {
  public static final int WINDOWS = 10, MAC = 20, LINUX = 30;

  public static final int detectOS() {
    String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
    if (os.contains("mac") || os.contains("darwin") || os.contains("os x")) {
      return MAC;
    } else if (os.contains("linux")) {
      return LINUX;
    } else if (os.contains("windows")) {
      return WINDOWS;
    } else {
      return -1;
    }
  }

  public static void openFile(String file, String folder) throws Exception {
    int os = detectOS();
    File f = new File(folder, file);
    switch (os) {
      case WINDOWS:
        WinUtils.open(f);
        break;
      case LINUX:
        LinuxUtils.open(f);
        break;
      case MAC:
        MacUtils.open(f);
        break;
      default:
        Desktop.getDesktop().open(f);
    }
  }

  public static void openFolder(String file, String folder) throws Exception {
    int os = detectOS();
    switch (os) {
      case WINDOWS:
        WinUtils.openFolder(folder, file);
        break;
      case LINUX:
        File f = new File(folder);
        LinuxUtils.open(f);
        break;
      case MAC:
        MacUtils.openFolder(folder, file);
        break;
      default:
        File ff = new File(folder);
        Desktop.getDesktop().open(ff);
    }
  }

  public static void copyURL(String url) {
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
    } catch (Exception e) {
      Logger.log(e);
    }
  }

  public static boolean exec(String args) {
    try {
      Logger.log("Launching: " + args);
      Runtime.getRuntime().exec(args);
    } catch (IOException e) {
      Logger.log(e);
      return false;
    }
    return true;
  }



  public static void keepAwakePing() {
    try {
      int os = detectOS();
      if (os == LINUX) {
        LinuxUtils.keepAwakePing();
      } else if (os == WINDOWS) {
        WinUtils.keepAwakePing();
      } else if (os == MAC) {
        MacUtils.keepAwakePing();
      }
    } catch (Throwable e) {
      // Logger.log(e);
    }
  }

  public static boolean isAlreadyAutoStart() {
    try {
      int os = detectOS();
      if (os == LINUX) {
        return LinuxUtils.isAlreadyAutoStart();
      } else if (os == WINDOWS) {
        return WinUtils.isAlreadyAutoStart();
      } else if (os == MAC) {
        return MacUtils.isAlreadyAutoStart();
      }
      return false;
    } catch (Throwable e) {
      Logger.log(e);
    }
    return false;
  }

  public static void addToStartup() {
    try {
      int os = detectOS();
      if (os == LINUX) {
        LinuxUtils.addToStartup();
      } else if (os == WINDOWS) {
        WinUtils.addToStartup();
      } else if (os == MAC) {
        MacUtils.addToStartup();
      }
    } catch (Throwable e) {
      Logger.log(e);
    }
  }

  public static void removeFromStartup() {
    try {
      int os = detectOS();
      if (os == LINUX) {
        LinuxUtils.removeFromStartup();
      } else if (os == WINDOWS) {
        WinUtils.removeFromStartup();
      } else if (os == MAC) {
        MacUtils.removeFromStartup();
      }
    } catch (Throwable e) {
      Logger.log(e);
    }
  }

  public static File getJarFile() {
    try {
      return new File(
          App.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
    } catch (URISyntaxException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    return null;
  }

  public static String getClipBoardText() {
    try {
      return (String)
          Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    } catch (Exception e) {
      Logger.log(e);
    }
    return "";
  }

  public static void browseURL(String url) {
    int os = detectOS();
    if (os == WINDOWS) {
      WinUtils.browseURL(url);
    } else if (os == LINUX) {
      LinuxUtils.browseURL(url);
    } else if (os == MAC) {
      MacUtils.browseURL(url);
    }
  }

  public static boolean below7() {
    try {
      int version = Integer.parseInt(System.getProperty("os.version").split("\\.")[0]);
      return (version < 6);
    } catch (Exception e) {

    }
    return false;
  }



  public static boolean isMacPopupTrigger(MouseEvent e) {
    if (detectOS() == MAC) {
      return (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0
          && (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
      // return (e.getModifiers() & InputEvent.BUTTON1_MASK) != 0 &&
      // (e.getModifiers()
      // & InputEvent.CTRL_MASK) != 0;
    }
    return false;
  }

  public static void mkdirs(String folder) {
    File outFolder = new File(folder);
    if (!outFolder.exists()) {
      outFolder.mkdirs();
    }
  }
}
