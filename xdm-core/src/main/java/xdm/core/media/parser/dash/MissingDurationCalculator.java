package xdm.core.media.parser.dash;

import java.util.*;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class MissingDurationCalculator {
  private MissingDurationCalculator() {}

  public static List<Long> calculatePeriodDurationsIfMissing(
      NodeList periods, long mediaPresentationDuration) {
    Deque<Node> stack = new ArrayDeque<>();
    for (int i = 0; i < periods.getLength(); i++) {
      stack.push(periods.item(i));
    }
    List<Long> list = new ArrayList<>(periods.getLength());
    long last = mediaPresentationDuration;
    int count = stack.size();
    for (int i = 0; i < count; i++) {
      Node node = stack.pop();
      String duration1 = XmlUtil.getAttr(node, "duration");
      String sstart = XmlUtil.getAttr(node, "start");
      if (sstart == null && duration1 == null) {
        throw new ParseException("Both period start and duration is missing");
      }
      if (duration1 != null) {
        long duration = DashUtil.parseXsDuration(duration1);
        list.add(duration);
        last = mediaPresentationDuration - duration;
        continue;
      }
      if (sstart == null && i == stack.size() - 1) {
        sstart = "PT0S";
      }
      long start = DashUtil.parseXsDuration(sstart);
      list.add(last - start);
      last = start;
    }
    Collections.reverse(list);
    return list;
  }
}
