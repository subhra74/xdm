package xdm.core.util;

import java.util.Locale;
import java.util.Objects;

public final class StringUtils {
  private StringUtils() {}

  public static boolean isNullOrEmpty(String str) {
    return str == null || str.isEmpty();
  }

  public static boolean isNullOrEmptyOrBlank(String str) {
    return str == null || str.trim().isEmpty();
  }

  public static byte[] getBytes(CharSequence sb) {
    return sb.toString().getBytes();
  }

  public static byte[] getBytes(String s) {
    return s.getBytes();
  }

  public static boolean containsIgnoreCase(String text, String match) {
    if (Objects.isNull(text) || Objects.isNull(match) || text.isEmpty() || match.isEmpty()) {
      return false;
    }
    return text.toLowerCase(Locale.ENGLISH).contains(match.toLowerCase(Locale.ENGLISH));
  }

  public static boolean equalsIgnoreCase(String str1, String str2) {
    if (str1 == null || str2 == null) {
      return false;
    }
    return str1.equalsIgnoreCase(str2);
  }
}
