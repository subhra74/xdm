//package xdm.core.media.parser.dash;
//
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.List;
//import org.w3c.dom.Node;
//import org.w3c.dom.NodeList;
//
//public class PeriodParser {
//  private PeriodParser() {}
//
//  public static List<MpdEntry> parsePeriod(
//      Node period, URI baseUrl, long mediaPresentationDuration) {
//    long periodDuration = mediaPresentationDuration;
//    String durationAttr = XmlUtil.getAttr(period, "duration");
//    if (durationAttr != null) {
//      periodDuration = DashUtil.parseXsDuration(durationAttr);
//    }
//    baseUrl = XmlUtil.resolveBaseUrl(period, baseUrl);
//    boolean hasParentSegmentBase = XmlUtil.containsTag(period, "SegmentBase");
//    NodeList adaptationSets = ((org.w3c.dom.Element) period).getElementsByTagName("AdaptationSet");
//    List<Representation> audioList = new ArrayList<>();
//    List<Representation> videoList = new ArrayList<>();
//    for (int i = 0; i < adaptationSets.getLength(); i++) {
//      List<Representation> representations =
//          RepresentationParser.parseAdaptationSet(
//              adaptationSets.item(i), baseUrl, periodDuration, hasParentSegmentBase);
//      if (representations.isEmpty()) continue;
//      if (representations.get(0).getMimeType().startsWith("audio")) {
//        audioList.addAll(representations);
//      } else {
//        videoList.addAll(representations);
//      }
//    }
//    List<MpdEntry> mediaList = new ArrayList<>();
//    if (!videoList.isEmpty() && !audioList.isEmpty()) {
//      for (Representation video : videoList) {
//        for (Representation audio : audioList) {
//          mediaList.add(new MpdEntry(video, audio));
//        }
//      }
//    } else if (!videoList.isEmpty()) {
//      for (Representation video : videoList) {
//        mediaList.add(new MpdEntry(video, null));
//      }
//    } else if (!audioList.isEmpty()) {
//      for (Representation audio : audioList) {
//        mediaList.add(new MpdEntry(null, audio));
//      }
//    }
//    return mediaList;
//  }
//}
