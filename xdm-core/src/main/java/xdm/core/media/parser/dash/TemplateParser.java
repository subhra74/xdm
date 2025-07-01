package xdm.core.media.parser.dash;

import xdm.core.util.StringUtils;

import java.util.List;

public class TemplateParser {
  private TemplateParser() {}

  public static String parseTemplate(
      List<MatcherResult> matches,
      String templateUrl,
      long number,
      long time,
      String bandwidth,
      String representationId) {
    for (MatcherResult match : matches) {
      String variable = match.group(1);
      if (variable.startsWith("Number")) {
        if (variable.equals("Number")) {
          templateUrl = templateUrl.replace("$" + variable + "$", Long.toString(number));
        } else {
          String num = formatDigit(number, match, true);
          templateUrl = templateUrl.replace("$" + variable + "$", num);
        }
      } else if (variable.startsWith("Time")) {
        if (variable.equals("Time")) {
          templateUrl = templateUrl.replace("$" + variable + "$", Long.toString(time));
        } else {
          String num = formatDigit(time, match, false);
          templateUrl = templateUrl.replace("$" + variable + "$", num);
        }
      } else if (variable.equals("RepresentationID")) {
        templateUrl = templateUrl.replace("$" + variable + "$", representationId);
      } else if (variable.equals("Bandwidth")) {
        templateUrl = templateUrl.replace("$" + variable + "$", bandwidth);
      }
    }
    return templateUrl;
  }

  private static String formatDigit(long digit, MatcherResult match, boolean number) {
    String digitWidth = match.group(number ? "numdigits" : "timedigits");
    String dx = "d";
    if ((number && match.group("numdx") != null) || (!number && match.group("timedx") != null)) {
      dx = number ? match.group("numdx") : match.group("timedx");
    }
    int width = 0;
    if (!StringUtils.isNullOrEmpty(digitWidth)) {
      width = Integer.parseInt(digitWidth);
    }
    if (width > 0) {
      String fmt = String.format("%s%s%s", "%0", width, dx);
      return String.format(fmt, digit);
    } else {
      return Long.toString(digit);
    }
  }
}
