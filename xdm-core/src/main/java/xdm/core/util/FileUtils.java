package xdm.core.util;

import java.io.File;
import java.net.URLDecoder;
import java.util.Set;

public final class FileUtils {
  private FileUtils() {}

  private static final Set<Character> invalid_chars =
      CollectionUtils.setOf('/', '\\', '"', '?', '*', '<', '>', ':', '|');

  public static String sanitizeFileName(String name) {
    if (name == null) return null;
    char[] arr = name.toCharArray();
    for (int i = 0; i < arr.length; i++) {
      if (invalid_chars.contains(arr[i])) {
        arr[i] = '_';
      }
    }
    return new String(arr);
  }

  public static String getUniqueFileName(String folder, String fileName) {
    File f = new File(folder, fileName);
    String ext = XDMUtils.getExtension(fileName);
    String name = XDMUtils.getFileNameWithoutExtension(fileName);
    if (ext == null) {
      ext = "";
    }
    int c = 0;
    while (f.exists()) {
      c++;
      f = new File(folder, name + "_" + c + ext);
    }
    return c == 0 ? fileName : name + "_" + c + ext;
  }

  public static String getFileName(String uri) {
    try {
      if (uri == null) return "FILE";
      if (uri.equals("/") || uri.isEmpty()) {
        return "FILE";
      }
      int x = uri.lastIndexOf("/");
      String path = uri;
      if (x > -1) {
        path = uri.substring(x);
      }
      int qindex = path.indexOf("?");
      if (qindex > -1) {
        path = path.substring(0, qindex);
      }
      path = decodeFileName(path);
      if (path.isEmpty()) return "FILE";
      if (path.equals("/")) return "FILE";
      return sanitizeFileName(path);
    } catch (Exception e) {
      Logger.log(e);
      return "FILE";
    }
  }

  public static String decodeFileName(String encoded) {
    String str;
    try {
      str = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8");
    } catch (Exception e) {
      StringBuilder builder = new StringBuilder();
      char[] ch = encoded.toCharArray();
      int i = 0;
      while (i < ch.length) {
        if (ch[i] == '%' && i + 2 < ch.length) {
          int c = Integer.parseInt(ch[i + 1] + "" + ch[i + 2], 16);
          builder.append((char) c);
          i += 2;
          continue;
        }
        builder.append(ch[i]);
        i++;
      }
      str = builder.toString();
    }
    StringBuilder builder = new StringBuilder();
    for (char c : str.toCharArray()) {
      if (c == '/' || c == '\\' || c == '"' || c == '?' || c == '*' || c == '<' || c == '>'
          || c == ':') continue;
      builder.append(c);
    }
    return builder.toString();
  }
}
