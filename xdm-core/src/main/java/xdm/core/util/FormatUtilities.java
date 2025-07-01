package xdm.core.util;

import java.text.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class FormatUtilities {
  private static SimpleDateFormat _format;
  private static DateTimeFormatter shortFormat = DateTimeFormatter.ofPattern("MMM dd");
  private static DateTimeFormatter longFormat = DateTimeFormatter.ofPattern("dd MMM yyyy");
  private static final int MB = 1024 * 1024, KB = 1024;

  public static synchronized String formatDate(long date) {
    if (_format == null) {
      _format = new SimpleDateFormat("yyyy-MM-dd");
    }
    Date dt = new Date(date);
    return _format.format(dt);
  }

  public static synchronized String formatDateShort(long epoch) {
    LocalDate date = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate();
    if (date.getYear() == LocalDate.now().getYear()) {
      return shortFormat.format(date);
    }
    return longFormat.format(date);
  }

  public static String formatSize(double length) {
    if (length < 0) return "---";
    if (length > MB) {
      return String.format("%.1f MB", (float) length / MB);
    } else if (length > KB) {
      return String.format("%.1f KB", (float) length / KB);
    } else {
      return String.format("%d B", (int) length);
    }
  }

  public static long getEtaAsSec(double length, float rate) {
    if (length == 0) return 0;
    if (length < 1 || rate <= 0) return -1;
    return (long) Math.ceil(length / rate);
  }

  public static String getETA(double length, float rate) {
    if (length == 0) return "00:00:00";
    if (length < 1 || rate <= 0) return "---";
    int sec = (int) Math.ceil(length / rate);
    return hms(sec);
  }

  public static String toLongEta(long sec) {
    long hrs = sec / 3600;
    long min = (sec % 3600) / 60;
    sec = sec % 60;
    List<String> arr = new ArrayList<>(4);
    if (hrs > 0) {
      arr.add(hrs + "h");
    }
    if (min > 0) {
      arr.add(min + "m");
    }
    if (hrs == 0 && min == 0) {
      arr.add(sec + "s");
    }
    return String.join(" ", arr);
  }

  public static String hms(int sec) {
    int hrs = 0, min = 0;
    hrs = sec / 3600;
    min = (sec % 3600) / 60;
    sec = sec % 60;
    String str = String.format("%02d:%02d:%02d", hrs, min, sec);
    return str;
  }

  public static String getResolution(String res) {
    if (res != null) {
      res = res.toLowerCase().trim();
      int index = res.indexOf("x");
      if (index > 0) {
        res = res.substring(index + 1).trim();
        try {
          Integer.parseInt(res);
          return res + "p";
        } catch (Exception e) {
        }
      }
    }
    return res;
  }

  public static String getFriendlyCodec(String name) {
    if (!StringUtils.isNullOrEmptyOrBlank(name)) {
      name = name.toLowerCase().trim();
      if (name.startsWith("avc")) {
        return "h264";
      }
      if (name.startsWith("mp4a")) {
        return "aac";
      }
      if (name.startsWith("mp4v")) {
        return "mpeg4";
      }
      if (name.startsWith("samr")) {
        return "amr";
      }
    }
    return name;
  }
}
