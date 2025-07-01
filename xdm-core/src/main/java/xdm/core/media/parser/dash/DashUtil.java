package xdm.core.media.parser.dash;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Node;

public class DashUtil {
  private DashUtil() {}

  private static final Pattern XS_DURATION_PATTERN =
      Pattern.compile(
          "^(-)?P((\\d*)Y)?((\\d*)M)?((\\d*)D)?(T((\\d*)H)?((\\d*)M)?(([0-9.]*)S)?)?$",
          Pattern.CASE_INSENSITIVE);

  private static final String TRICK_MODE_URL = "http://dashif.org/guidelines/trickmode";

  public static long parseXsDuration(String value) {
    Matcher match = XS_DURATION_PATTERN.matcher(value);
    if (match.matches()) {
      boolean negated = match.group(1) != null && !match.group(1).isEmpty();
      // Durations containing years and months aren't completely defined. We assume there are
      // 30.4368 days in a month, and 365.242 days in a year.
      String years = match.group(3);
      final double durationSeconds = getDurationSeconds(years, match);
      long durationMillis = (long) (durationSeconds * 1000);
      return negated ? -durationMillis : durationMillis;
    } else {
      return (long) (Double.parseDouble(value) * 3600 * 1000);
    }
  }

  private static double getDurationSeconds(String years, Matcher match) {
    double durationSeconds =
        (years != null && !years.isEmpty()) ? Double.parseDouble(years) * 31556908 : 0;
    String months = match.group(5);
    durationSeconds +=
        (months != null && !months.isEmpty()) ? Double.parseDouble(months) * 2629739 : 0;
    String days = match.group(7);
    durationSeconds += (days != null && !days.isEmpty()) ? Double.parseDouble(days) * 86400 : 0;
    String hours = match.group(10);
    durationSeconds += (hours != null && !hours.isEmpty()) ? Double.parseDouble(hours) * 3600 : 0;
    String minutes = match.group(12);
    durationSeconds +=
        (minutes != null && !minutes.isEmpty()) ? Double.parseDouble(minutes) * 60 : 0;
    String seconds = match.group(14);
    durationSeconds += (seconds != null && !seconds.isEmpty()) ? Double.parseDouble(seconds) : 0;
    return durationSeconds;
  }

  public static boolean containsTrickMode(Node xmlAdaptationSet) {
    Node xmlEssentialProperty = XmlUtil.getFirstTag(xmlAdaptationSet, "EssentialProperty");
    if (xmlEssentialProperty != null) {
      String schemeIdUri = XmlUtil.getAttr(xmlEssentialProperty, "schemeIdUri");
      if (TRICK_MODE_URL.equals(schemeIdUri)) {
        return true;
      }
    }
    Node xmlSupplementalProperty = XmlUtil.getFirstTag(xmlAdaptationSet, "SupplementalProperty");
    if (xmlSupplementalProperty != null) {
      String schemeIdUri = XmlUtil.getAttr(xmlSupplementalProperty, "schemeIdUri");
        return TRICK_MODE_URL.equals(schemeIdUri);
    }
    return false;
  }
}
