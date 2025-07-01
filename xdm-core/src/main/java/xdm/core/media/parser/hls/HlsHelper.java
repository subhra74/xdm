package xdm.core.media.parser.hls;

//
// import java.util.AbstractMap;
// import java.util.HashMap;
// import java.util.Map;
//
// public class HlsHelper {
//  public static Map<String, String> parseAttributes(String attrText) {
//    Map<String, String> dict = new HashMap<>();
//    StringBuilder pairBuf = new StringBuilder();
//    boolean insideQuote = false;
//    for (char ch : attrText.toCharArray()) {
//      if (ch == ',' && !insideQuote) {
//        AbstractMap.SimpleEntry<String, String> simpleEntry = parsePair(pairBuf.toString());
//        if (simpleEntry != null) {
//          dict.put(simpleEntry.getKey(), simpleEntry.getValue());
//        }
//        pairBuf = new StringBuilder();
//        continue;
//      }
//      if (ch == '"') insideQuote = !insideQuote;
//      pairBuf.append(ch);
//    }
//    if (pairBuf.length() > 0) {
//      AbstractMap.SimpleEntry<String, String> simpleEntry = parsePair(pairBuf.toString());
//      if (simpleEntry != null) {
//        dict.put(simpleEntry.getKey(), simpleEntry.getValue());
//      }
//    }
//    return dict;
//  }
//
//  private static AbstractMap.SimpleEntry<String, String> parsePair(String pair) {
//    int index = pair.indexOf('=');
//    if (index == -1) return null;
//    return new AbstractMap.SimpleEntry<>(
//        pair.substring(0, index).trim(), cleanAttrValue(pair.substring(index + 1)));
//  }
//
//  private static String cleanAttrValue(String value) {
//    return value.replace("\"", "").replace("'", "").replace(" ", "");
//  }
// }

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HlsHelper {
  private static final Pattern ATTRIBUTE_PATTERN =
      Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))");

  public static Map<String, String> parseAttributes(String attributeString) {
    Map<String, String> attributes = new HashMap<>();
    Matcher matcher = ATTRIBUTE_PATTERN.matcher(attributeString);

    while (matcher.find()) {
      String key = matcher.group(1);
      String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
      attributes.put(key, value);
    }

    return attributes;
  }

  public static String toBigEndian128BitHex(long number) {
    BigInteger bigInt = BigInteger.valueOf(number);
    byte[] byteArray = bigInt.toByteArray();

    if (byteArray.length > 0 && byteArray[0] == 0 && byteArray.length > 16) {
      byteArray = Arrays.copyOfRange(byteArray, 1, byteArray.length);
    }

    byte[] buffer = new byte[16];
    int srcPos = Math.max(0, byteArray.length - 16);
    int destPos = 16 - (byteArray.length - srcPos);
    int length = byteArray.length - srcPos;
    System.arraycopy(byteArray, srcPos, buffer, destPos, length);

    // Convert to hexadecimal string
    StringBuilder hexBuilder = new StringBuilder();
    for (byte b : buffer) {
      hexBuilder.append(String.format("%02X", b));
    }

    return hexBuilder.toString();
  }
}
