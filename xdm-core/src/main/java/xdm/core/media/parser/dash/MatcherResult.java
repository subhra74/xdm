package xdm.core.media.parser.dash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class MatcherResult {
  private List<String> groups = new ArrayList<>();
  private Map<String, String> namedGroups = new HashMap<>();

  public String group(int index) {
    return groups.get(index);
  }

  public String group(String name) {
    return namedGroups.get(name);
  }

  private MatcherResult() {}

  public static MatcherResult toMatcherResult(Matcher matcher) {
    List<String> groups = new ArrayList<>();
    Map<String, String> namedGroups = new HashMap<>();
    for (int i = 0; i < matcher.groupCount(); i++) {
      groups.add(matcher.group(i));
    }
    namedGroups.put("timedx", matcher.group("timedx"));
    namedGroups.put("timedigits", matcher.group("timedigits"));
    namedGroups.put("numdx", matcher.group("numdx"));
    namedGroups.put("numdigits", matcher.group("numdigits"));
    MatcherResult matcherResult = new MatcherResult();
    matcherResult.groups = groups;
    matcherResult.namedGroups = namedGroups;
    return matcherResult;
  }
}
