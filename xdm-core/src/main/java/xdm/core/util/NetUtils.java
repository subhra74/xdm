package xdm.core.util;

import xdm.core.network.http.HeaderCollection;

import java.io.*;

public class NetUtils {
  public static byte[] getBytes(String str) {
    return str.getBytes();
  }

  public static final String readLine(InputStream in) throws IOException {
    StringBuffer buf = new StringBuffer();
    while (true) {
      int x = in.read();
      if (x == -1) throw new IOException("Unexpected EOF while reading header line");
      if (x == '\n') return buf.toString();
      if (x != '\r') buf.append((char) x);
    }
  }

  public static final int getResponseCode(String statusLine) {
    String arr[] = statusLine.split(" ");
    if (arr.length < 2) return 400;
    return Integer.parseInt(arr[1]);
  }

  public static long getContentLength(HeaderCollection headers) {
    try {
      String clen = headers.getValue("content-length");
      if (clen != null) {
        return Long.parseLong(clen);
      } else {
        clen = headers.getValue("content-range");
        if (clen != null) {
          String str = clen.split(" ")[1];
          str = str.split("/")[0];
          String arr[] = str.split("-");
          return Long.parseLong(arr[1]) - Long.parseLong(arr[0]) + 1;
        } else {
          return -1;
        }
      }
    } catch (Exception e) {
      return -1;
    }
  }

  public static void skipRemainingStream(InputStream inStream, long length) throws IOException {
    byte buf[] = new byte[8192];
    if (length > 0) {
      while (length > 0) {
        int r = (int) (length > buf.length ? buf.length : length);
        int x = inStream.read(buf, 0, r);
        if (x == -1) break;
        length -= x;
      }
    } else {
      while (true) {
        int x = inStream.read(buf);
        if (x == -1) break;
      }
    }
  }

  private static String getExtendedContentDisposition(String header) {
    try {
      String arr[] = header.split(";");
      for (String str : arr) {
        if (str.contains("filename*")) {
          int index = str.lastIndexOf("'");
          if (index > 0) {
            String st = str.substring(index + 1);
            return XDMUtils.decodeFileName(st);
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  public static String getNameFromContentDisposition(String header) {
    try {
      if (header == null) return null;
      String headerLow = header.toLowerCase();
      if (headerLow.startsWith("attachment") || headerLow.startsWith("inline")) {
        String name = getExtendedContentDisposition(header);
        if (name != null) return name;
        String arr[] = header.split(";");
        for (int i = 0; i < arr.length; i++) {
          String str = arr[i].trim();
          if (str.toLowerCase().startsWith("filename")) {
            int index = str.indexOf('=');
            String file = str.substring(index + 1).replace("\"", "").trim();
            try {
              return XDMUtils.decodeFileName(file);
            } catch (Exception e) {
              return file;
            }
          }
        }
      }
    } catch (Exception e) {
    }
    return null;
  }

  public static String getCleanContentType(String contentType) {
    if (contentType == null || contentType.length() < 1) return contentType;
    int index = contentType.indexOf(";");
    if (index > 0) {
      contentType = contentType.substring(0, index).trim().toLowerCase();
    }
    return contentType;
  }
}
