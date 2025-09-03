package xdm.integration;

import xdm.core.util.Logger;
import xdm.core.util.StringUtils;

import java.io.*;
import java.net.*;
import java.util.*;

public class HttpParser {
  public static String parseRequestStatusLine(String statusLine) throws IOException {
    try {
      String[] arr = statusLine.split(" ");
      if (arr.length > 2) {
        return arr[1];
      }
    } catch (Exception ex) {
      Logger.info(ex);
    }
    throw new IOException("Invalid HTTP status line: " + statusLine);
  }

  static void parseHeader(String headerLine, HeaderResult result) throws IOException {
    int index = headerLine.indexOf(":");
    if (index > 0) {
      result.key = headerLine.substring(0, index).trim();
      result.value = headerLine.substring(index + 1).trim();
      return;
    }
    throw new IOException("Invalid header");
  }

  static long parseContentLength(Map<String, List<String>> headers) {
    List<String> values = headers.get("Content-Length");
    String value = (values != null && !values.isEmpty()) ? values.get(0) : null;
    return Long.parseLong(value != null ? value : "-1");
  }

  private static boolean shouldKeepAlive(Map<String, List<String>> headers) {
    List<String> values = headers.get("Connection");
    String value = (values != null && !values.isEmpty()) ? values.get(0) : "close";
    return "keep-alive".equalsIgnoreCase(value);
  }

  static RequestContext parseContext(Socket socket) throws IOException {
    String path = "/";
    Map<String, List<String>> headers = new HashMap<>();
    byte[] body = null;
    InputStream io = socket.getInputStream();
    boolean first = true;

    while (true) {
      String line = LineReader.readLine(io);
      if (StringUtils.isNullOrEmpty(line)) break;
      if (first) {
        path = parseRequestStatusLine(line);
        first = false;
        continue;
      }

      HeaderResult headerResult = new HeaderResult();
      parseHeader(line, headerResult);

      List<String> values = headers.getOrDefault(headerResult.key, new ArrayList<>());
      values.add(headerResult.value);
      headers.put(headerResult.key, values);
    }

    long contentLength = parseContentLength(headers);
    if (contentLength > 0) {
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        copyTo(io, baos, contentLength);
        body = baos.toByteArray();
      }
    }

    return new RequestContext(path, headers, body, socket, shouldKeepAlive(headers));
  }

  static void copyTo(InputStream source, OutputStream destination, long limit) throws IOException {
    byte[] buffer = new byte[8192];
    int read;
    while (limit > 0
        && (read = source.read(buffer, 0, (int) Math.min(buffer.length, limit))) != -1) {
      destination.write(buffer, 0, read);
      limit -= read;
    }
  }

  // Helper class to return multiple values from parseHeader method
  static class HeaderResult {
    String key;
    String value;
  }
}
