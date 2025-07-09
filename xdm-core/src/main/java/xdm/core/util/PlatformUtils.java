package xdm.core.util;

import xdm.core.constants.AppConstants;

import java.io.File;
import java.net.URISyntaxException;

public final class PlatformUtils {
  private PlatformUtils() {}

  public static String getConfigDir() {
    return new File(System.getProperty("user.home"), AppConstants.CONFIG_DIR).getAbsolutePath();
  }

  public static String getMetaDir() {
    return new File(
            System.getProperty("user.home"),
            AppConstants.CONFIG_DIR + File.separator + AppConstants.META_DIR)
        .getAbsolutePath();
  }

  public static String getExecutableFromSystemPath(String executable) {
    String path = System.getenv("PATH");
    if (!StringUtils.isNullOrEmpty(path)) {
      for (String spath : path.split(File.pathSeparator)) {
        File f = new File(spath, executable);
        if (f.exists()) {
          return f.getAbsolutePath();
        }
      }
    }
    return null;
  }

  public static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("windows");
  }

  public static File getBaseDirectory() {
    try {
      File f =
          new File(
              PlatformUtils.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI()
                  .getPath());
      return f.getParentFile();
    } catch (URISyntaxException e) {
      // No action
    }
    return null;
  }
}
