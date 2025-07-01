package xdm.app.util;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import xdm.core.Config;
import xdm.core.util.Logger;
import xdm.core.util.XDMUtils;

public class UpdateChecker {

  public static final int APP_UPDATE_AVAILABLE = 10,
      COMP_UPDATE_AVAILABLE = 20,
      COMP_NOT_INSTALLED = 30,
      NO_UPDATE_AVAILABLE = 40;

  private static final Pattern PATTERN_TAG =
      Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"(\\d+\\.\\d+\\.\\d+)\\\"");

  public static int getUpdateStat() {
    System.out.println("checking for app update");
    if (isAppUpdateAvailable()) return APP_UPDATE_AVAILABLE;
    return NO_UPDATE_AVAILABLE;
    //		int stat = isComponentUpdateAvailable();
    //		System.out.println("Stat: " + stat);
    //		if (stat == 0) {
    //			return COMP_UPDATE_AVAILABLE;
    //		} else if (stat == -1) {
    //			return COMP_NOT_INSTALLED;
    //		} else {
    //			System.out.println("checking for app update");
    //			if (isAppUpdateAvailable())
    //				return APP_UPDATE_AVAILABLE;
    //			return NO_UPDATE_AVAILABLE;
    //		}
  }

  private static boolean isAppUpdateAvailable() {
    return isUpdateAvailable(Config.APP_VERSION);
  }

  // return 1 is no update required
  // return 0, -1 if update required
  private static int isComponentUpdateAvailable() {
    String componentVersion = getComponentVersion();
    System.out.println("current component version: " + componentVersion);
    if (componentVersion == null) return -1;
    return isUpdateAvailable(componentVersion) ? 0 : 1;
  }

  public static String getComponentVersion() {
    File f = new File(Config.getInstance().getDataFolder());
    String[] files =
        f.list(
            new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.endsWith(".version");
              }
            });
    if (files == null || files.length < 1) {
      Logger.log("Component not installed");
      Logger.log("Checking fallback components");
      return getFallbackComponentVersion();
    }
    return files[0].split("\\.")[0];
  }

  public static String getFallbackComponentVersion() {
    File f = PlatformUtils.getJarFile().getParentFile();
    String[] files =
        f.list(
            new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.endsWith(".version");
              }
            });
    if (files == null || files.length < 1) {
      Logger.log("Component not installed");
      return null;
    }

    return files[0].split("\\.")[0];
  }

  private static boolean isUpdateAvailable(String version) {
    HttpGet request = new HttpGet(Config.APP_VERSION + "?ver=" + version);
    var context = new HttpClientContext();
    try (var hc = HttpClients.createDefault();
        var res = hc.executeOpen(null, request, context);
        var in = res.getEntity().getContent()) {
      var responseText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return isNewerVersion(responseText, Config.APP_VERSION);
    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }

  private static boolean isNewerVersion(String text, String v2) {
    try {
      // System.out.println(text);
      Matcher matcher = PATTERN_TAG.matcher(text);
      if (matcher.find()) {
        String v1 = matcher.group(1);
        System.out.println(v1 + " " + v2);
        if (v1.indexOf(".") > 0 && v2.indexOf(".") > 0) {
          String[] arr1 = v1.split("\\.");
          String[] arr2 = v2.split("\\.");
          for (int i = 0; i < Math.min(arr1.length, arr2.length); i++) {
            int ia = Integer.parseInt(arr1[i]);
            int ib = Integer.parseInt(arr2[i]);
            if (ia > ib) {
              return true;
            }
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return false;
  }
}
