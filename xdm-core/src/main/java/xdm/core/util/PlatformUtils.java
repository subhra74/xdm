package xdm.core.util;

import java.io.File;
import java.net.URISyntaxException;

public final class PlatformUtils {
  private PlatformUtils() {}

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
