package xdm.core.util;

import xdm.core.Config;
import xdm.core.XDMConstants;
import xdm.core.downloaders.metadata.HttpDownloadMetadata;

import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

public class XDMUtils {
  // private static float dpiScale;
  // private static Map<Integer, String> categoryFolderMap;
  //
  // static {
  // categoryFolderMap = new HashMap<>();
  // categoryFolderMap.put(XDMConstants.DOCUMENTS, "Documents");
  // categoryFolderMap.put(XDMConstants.MUSIC, "Music");
  // categoryFolderMap.put(XDMConstants.VIDEO, "Videos");
  // categoryFolderMap.put(XDMConstants.PROGRAMS, "Programs");
  // categoryFolderMap.put(XDMConstants.COMPRESSED, "Compressed");
  // }
  //
  // public static String getFolderForCategory(int category) {
  // return categoryFolderMap.get(category);
  // }

  //	static {
  //		//Fixed issue with DPI scaling in Java 9 and higher
  //		int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
  //		dpiScale = dpi / 96F;
  //		//System.out.println("DPI init " + dpiScale);
  //	}

  private static final char[] invalid_chars = {'/', '\\', '"', '?', '*', '<', '>', ':', '|'};

  public static String decodeFileName(String encoded) {
    String str;
    try {
      str = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8");
    } catch (Exception e) {
      StringBuilder builder = new StringBuilder();
      char[] ch = encoded.toCharArray();
      for (int i = 0; i < ch.length; i++) {
        if (ch[i] == '%') {
          if (i + 2 < ch.length) {
            int c = Integer.parseInt(ch[i + 1] + "" + ch[i + 2], 16);
            builder.append((char) c);
            i += 2;
            continue;
          }
        }
        builder.append(ch[i]);
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

  public static String getFileName(String uri) {
    try {
      if (uri == null) return "FILE";
      if (uri.equals("/") || uri.length() < 1) {
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
      if (path.length() < 1) return "FILE";
      if (path.equals("/")) return "FILE";
      return createSafeFileName(path);
    } catch (Exception e) {
      Logger.info(e);
      return "FILE";
    }
  }

  public static String createSafeFileName(String str) {
    String safe_name = str;
    for (int i = 0; i < invalid_chars.length; i++) {
      if (safe_name.indexOf(invalid_chars[i]) != -1) {
        safe_name = safe_name.replace(invalid_chars[i], '_');
      }
    }
    return safe_name;
  }

  public static boolean validateURL(String url) {
    try {
      url = url.toLowerCase();
      if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("ftp://")) {
        new URL(url);
        return true;
      }
      return false;
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  static String doc[] = {
    ".doc", ".docx", ".txt", ".pdf", ".rtf", ".xml", ".c", ".cpp", ".java", ".cs", ".vb", ".html",
    ".htm", ".chm", ".xls", ".xlsx", ".ppt", ".pptx", ".js", ".css"
  };
  static String cmp[] = {
    ".7z", ".zip", ".rar", ".gz", ".tgz", ".tbz2", ".bz2", ".lzh", ".sit", ".z"
  };
  static String music[] = {
    ".mp3", ".wma", ".ogg", ".aiff", ".au", ".mid", ".midi", ".mp2", ".mpa", ".wav", ".aac", ".oga",
    ".ogx", ".ogm", ".spx", ".opus"
  };
  static String vid[] = {
    ".mpg", ".mpeg", ".avi", ".flv", ".asf", ".mov", ".mpe", ".wmv", ".mkv", ".mp4", ".3gp",
    ".divx", ".vob", ".webm", ".ts"
  };
  static String prog[] = {
    ".exe", ".msi", ".bin", ".sh", ".deb", ".cab", ".cpio", ".dll", ".jar", "rpm", ".run", ".py"
  };

  public static int findCategory(String filename) {
    String file = filename.toLowerCase();
    for (int i = 0; i < doc.length; i++) {
      if (file.endsWith(doc[i])) {
        return XDMConstants.DOCUMENTS;
      }
    }
    for (int i = 0; i < cmp.length; i++) {
      if (file.endsWith(cmp[i])) {
        return XDMConstants.COMPRESSED;
      }
    }
    for (int i = 0; i < music.length; i++) {
      if (file.endsWith(music[i])) {
        return XDMConstants.MUSIC;
      }
    }
    for (int i = 0; i < prog.length; i++) {
      if (file.endsWith(prog[i])) {
        return XDMConstants.PROGRAMS;
      }
    }
    for (int i = 0; i < vid.length; i++) {
      if (file.endsWith(vid[i])) {
        return XDMConstants.VIDEO;
      }
    }
    return XDMConstants.OTHER;
  }

  public static String appendArray2Str(String[] arr) {
    boolean first = true;
    StringBuffer buf = new StringBuffer();
    for (String s : arr) {
      if (!first) {
        buf.append(",");
      }
      buf.append(s);
      first = false;
    }
    return buf.toString();
  }

  public static String[] appendStr2Array(String str) {
    String[] arr = str.split(",");
    ArrayList<String> arrList = new ArrayList<String>();
    for (String s : arr) {
      String txt = s.trim();
      if (txt.length() > 0) {
        arrList.add(txt);
      }
    }
    arr = new String[arrList.size()];
    return arrList.toArray(arr);
  }

  public static String getExtension(String file) {
    int index = file.lastIndexOf(".");
    if (index > 0) {
      return file.substring(index);
    } else {
      return null;
    }
  }

  public static String getFileNameWithoutExtension(String fileName) {
    int index = fileName.lastIndexOf(".");
    if (index > 0) {
      fileName = fileName.substring(0, index);
      return fileName;
    } else {
      return fileName;
    }
  }

  public static void copyStream(InputStream instream, OutputStream outstream, long size)
      throws Exception {
    byte[] b = new byte[8192];
    long rem = size;
    while (true) {
      int bs = (int) (size > 0 ? (rem > b.length ? b.length : rem) : b.length);
      int x = instream.read(b, 0, bs);
      if (x == -1) {
        if (size > 0) {
          throw new EOFException("Unexpected EOF");
        } else {
          break;
        }
      }
      outstream.write(b, 0, x);
      rem -= x;
      if (size > 0) {
        if (rem <= 0) break;
      }
    }
  }

  public static final int getOsArch() {
    if (System.getProperty("os.arch").contains("64")) {
      return 64;
    } else {
      return 32;
    }
  }

  //	public static int detectScreenType() {
  ////		if (screenType < 0) {
  //////			int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
  //////			dpiScale = dpi / 96.0f;
  ////			Logger.log("Dpi scale init: " + dpiScale);
  ////			// Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
  ////			// double height = d.getHeight();
  ////			if (dpiScale >= 2) {
  ////				screenType = XDMConstants.XHDPI;
  ////			} else if (dpiScale >= 1.25) {
  ////				screenType = XDMConstants.HDPI;
  ////			} else {
  ////				screenType = XDMConstants.NORMAL;
  ////			}
  ////		}
  //		return screenType;
  //	}

  public static List<HttpDownloadMetadata> toMetadata(List<String> urls) {
    List<HttpDownloadMetadata> list = new ArrayList<>();
    for (String url : urls) {
      HttpDownloadMetadata md = new HttpDownloadMetadata();
      md.setUrl(url);
      list.add(md);
    }
    return list;
  }

  private static int screenType = -1;

  public static final int getScaledInt(int value) {
    return value;
    // System.err.println("in: " + value);
    //		if (dpiScale == 0.0f) {
    //			int dpi = Toolkit.getDefaultToolkit().getScreenResolution();
    //			dpiScale = dpi / 96;
    //		}
    // System.err.println("out: " + (value * dpiScale) + " dpi: " + dpiScale);
    // return (int) (value * dpiScale);
  }

  /*
   * public static final int getScaledInt(int size) { detectScreenType();
   * return (int) (size * getScaleFactor()); }
   */

  public static final String readLineSafe(BufferedReader r) throws IOException {
    String ln = r.readLine();
    if (ln == null) {
      throw new IOException("Unexpected EOF");
    }
    return ln;
  }

  public static int clamp(int value, int min, int max) {
    value = Math.max(min, value);
    return Math.min(value, max);
  }

  public static void mkdirs(String folder) {
    File outFolder = new File(folder);
    if (!outFolder.exists()) {
      outFolder.mkdirs();
    }
  }

  public static long getFreeSpace(String folder) {
    if (folder == null) return new File(Config.getInstance().getTemporaryFolder()).getFreeSpace();
    else return new File(folder).getFreeSpace();
  }
}
