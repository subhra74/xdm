package xdm.app.utils;

import xdman.util.Logger;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;

public final class PlatformUtils {
  public static String getClipBoardText() {
    try {
      return (String)
          Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    } catch (Exception e) {
      Logger.log(e);
    }
    return "";
  }
}
