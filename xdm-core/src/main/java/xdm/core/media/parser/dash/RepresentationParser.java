package xdm.core.media.parser.dash;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import xdm.core.media.parser.util.UrlResolver;

public class RepresentationParser {
  private RepresentationParser() {}

  private static final String MEDIA_KEY = "media";
  private static final Pattern TemplatePattern =
      Pattern.compile(
          "\\$(RepresentationID|Time|Time%0(?<timedigits>\\d+)(?<timedx>[dx])?|Number|Number%0(?<numdigits>\\d+)(?<numdx>[dx])?|Bandwidth)\\$");

  public static List<Representation> parseAdaptationSet(
      Node xmlAdaptationSet, URI baseUrl, long periodDuration, boolean hasParentSegmentBase) {
    List<Representation> representations = new ArrayList<>();
    String baseUrlValue = XmlUtil.getFirstTagValue(xmlAdaptationSet, "BaseURL");
    if (baseUrlValue != null) {
      baseUrl = UrlResolver.resolve(baseUrl, baseUrlValue);
    }
    if (DashUtil.containsTrickMode(xmlAdaptationSet)) {
      return representations;
    }
    NodeList xmlRepresentations =
        ((org.w3c.dom.Element) xmlAdaptationSet).getElementsByTagName("Representation");
    for (int i = 0; i < xmlRepresentations.getLength(); i++) {
      Representation rep =
          parseRepresentation(
              xmlRepresentations.item(i), baseUrl, periodDuration, hasParentSegmentBase);
      if (rep != null) representations.add(rep);
    }
    return representations;
  }

  private static Optional<String> getAttr(
      NamedNodeMap attrs, NamedNodeMap pAttrs, String name) {
    return Optional.ofNullable(XmlUtil.getSelfOrParentAttr(attrs, pAttrs, name));
  }

  private static Representation parseSegmentList(
      Node xmlSegmentList,
      URI baseUrl,
      int width,
      int height,
      String codec,
      long bandwidth,
      long periodDuration,
      String mimeType,
      String lang) {
    NodeList segmentUrlNodes =
        ((org.w3c.dom.Element) xmlSegmentList).getElementsByTagName("SegmentURL");
    List<URI> segments = new ArrayList<>();
    Node xmlInit =
        XmlUtil.findTag(xmlSegmentList, Arrays.asList("Initialization", "RepresentationIndex"));
    if (xmlInit != null) {
      String sourceURL = XmlUtil.getAttr(xmlInit, "sourceURL");
      if (sourceURL != null) {
        segments.add(UrlResolver.resolve(baseUrl, sourceURL));
      }
    }
    for (int i = 0; i < segmentUrlNodes.getLength(); i++) {
      Node segmentNode = segmentUrlNodes.item(i);
      String media = XmlUtil.getAttr(segmentNode, MEDIA_KEY);
      if (media != null) {
        segments.add(UrlResolver.resolve(baseUrl, media));
      }
    }
    if (!segments.isEmpty()) {
      return new Representation(
          segments, width, height, codec, bandwidth, periodDuration, mimeType, lang);
    }
    return null;
  }

  private static Representation parseSegmentTimeLineSimple(
      Node xmlSegmentTemplate,
      NamedNodeMap attrs,
      URI baseUrl,
      int width,
      int height,
      String codec,
      long bandwidth,
      String bandwidthStr,
      long periodDuration,
      String mimeType,
      String lang) {
    String timescaleStr =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "timescale")).orElse("1");
    String durationStr =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "duration")).orElse("1");
    String startNumberStr =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "startNumber")).orElse("1");
    long timescale = Long.parseLong(timescaleStr);
    long duration = Long.parseLong(durationStr);
    long startNumber = Long.parseLong(startNumberStr);
    int segmentCount =
        (int) Math.ceil(((double) periodDuration / 1000) / ((double) duration / timescale));
    String representationId =
        attrs.getNamedItem("id") != null ? attrs.getNamedItem("id").getNodeValue() : "";
    long number = startNumber;
    long time = startNumber;
    String initUrl =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "initialization"))
            .map(s -> s.replace("$$", "\0"))
            .orElse(null);
    String mediaUrl =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, MEDIA_KEY))
            .map(s -> s.replace("$$", "\0"))
            .orElse(null);
    List<MatcherResult> mediaMatches = new ArrayList<>();
    if (mediaUrl != null) {
      Matcher m = TemplatePattern.matcher(mediaUrl);
      while (m.find()) mediaMatches.add(MatcherResult.toMatcherResult(m));
    }
    List<URI> segments = new ArrayList<>(segmentCount + (initUrl != null ? 1 : 0));
    if (initUrl != null) {
      List<MatcherResult> initMatches = new ArrayList<>();
      Matcher m = TemplatePattern.matcher(initUrl);
      while (m.find()) initMatches.add(MatcherResult.toMatcherResult(m));
      String initializationUrl =
          TemplateParser.parseTemplate(
                  initMatches, initUrl, number, time, bandwidthStr, representationId)
              .replace("\0", "$");
      segments.add(UrlResolver.resolve(baseUrl, initializationUrl));
    }
    for (int i = 0; i < segmentCount; i++) {
      String segmentUrl =
          TemplateParser.parseTemplate(
                  mediaMatches, mediaUrl, number, time, bandwidthStr, representationId)
              .replace("\0", "$ ");
      segments.add(UrlResolver.resolve(baseUrl, segmentUrl));
      number++;
      time += duration;
    }
    if (!segments.isEmpty()) {
      return new Representation(
          segments, width, height, codec, bandwidth, periodDuration, mimeType, lang);
    }
    return null;
  }

  private static List<MatcherResult> matchTemplateUrl(String url) {
    List<MatcherResult> arr = new ArrayList<>();
    if (url != null) {
      Matcher m = TemplatePattern.matcher(url);
      while (m.find()) arr.add(MatcherResult.toMatcherResult(m));
    }
    return arr;
  }

  private static Representation parseSegmentTimeLineExplicit(
      Node xmlSegmentTemplate,
      NodeList xmlSs,
      NamedNodeMap attrs,
      URI baseUrl,
      int width,
      int height,
      String codec,
      long bandwidth,
      String bandwidthStr,
      long periodDuration,
      String mimeType,
      String lang) {
    String representationId =
        Optional.ofNullable(attrs.getNamedItem("id")).map(Node::getNodeValue).orElse("");
    String startNumberStr =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "startNumber")).orElse("1");
    long number = Long.parseLong(startNumberStr);
    long time = 0L;

    String initUrl =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, "initialization"))
            .map(s -> s.replace("$$", "\0"))
            .orElse(null);
    String mediaUrl =
        Optional.ofNullable(XmlUtil.getAttr(xmlSegmentTemplate, MEDIA_KEY))
            .map(s -> s.replace("$$", "\0"))
            .orElse(null);
    List<MatcherResult> mediaMatches = matchTemplateUrl(mediaUrl);
    List<URI> segments = new ArrayList<>();
    if (initUrl != null) {
      List<MatcherResult> initMatches = matchTemplateUrl(initUrl);
      String initializationUrl =
          TemplateParser.parseTemplate(
                  initMatches, initUrl, number, time, bandwidthStr, representationId)
              .replace("\0", "$ ");
      segments.add(UrlResolver.resolve(baseUrl, initializationUrl));
    }
    for (int i = 0; i < xmlSs.getLength(); i++) {
      Node xmls = xmlSs.item(i);
      long d = Optional.ofNullable(XmlUtil.getAttr(xmls, "d")).map(Long::parseLong).orElse(0L);
      long t = Optional.ofNullable(XmlUtil.getAttr(xmls, "t")).map(Long::parseLong).orElse(-1L);
      long r = Optional.ofNullable(XmlUtil.getAttr(xmls, "r")).map(Long::parseLong).orElse(-1L);
      if (t > 0) time = t;
      String segmentUrl =
          TemplateParser.parseTemplate(
                  mediaMatches, mediaUrl, number, time, bandwidthStr, representationId)
              .replace("\0", "$ ");
      segments.add(UrlResolver.resolve(baseUrl, segmentUrl));
      number++;
      time += d;
      if (r > 0) {
        for (int k = 0; k < r; k++) {
          segmentUrl =
              TemplateParser.parseTemplate(
                      mediaMatches, mediaUrl, number, time, bandwidthStr, representationId)
                  .replace("\0", "$ ");
          segments.add(UrlResolver.resolve(baseUrl, segmentUrl));
          number++;
          time += d;
        }
      }
    }
    if (!segments.isEmpty()) {
      return new Representation(
          segments, width, height, codec, bandwidth, periodDuration, mimeType, lang);
    }

    return null;
  }

  public static Representation parseRepresentation(
      Node xmlRepresentation, URI baseUrl, long periodDuration, boolean hasParentSegmentBase) {
    NamedNodeMap attrs = xmlRepresentation.getAttributes();
    Node parent = xmlRepresentation.getParentNode();
    NamedNodeMap pAttrs = parent.getAttributes();
    String mimeType = getAttr(attrs, pAttrs, "mimeType").orElse("").toLowerCase();
    int width = getAttr(attrs, pAttrs, "width").map(Integer::parseInt).orElse(-1);
    int height = getAttr(attrs, pAttrs, "height").map(Integer::parseInt).orElse(-1);
    Optional<String> bw = getAttr(attrs, pAttrs, "bandwidth");
    String bandwidthStr = bw.orElse("");
    long bandwidth = bw.map(Long::parseLong).orElse(-1L);
    String codec = getAttr(attrs, pAttrs, "codecs").orElse("").toLowerCase();
    String lang = getAttr(attrs, pAttrs, "lang").orElse("").toLowerCase();

    if (!(mimeType.startsWith("audio") || mimeType.startsWith("video"))) {
      return null;
    }

    // BaseURL
    baseUrl = XmlUtil.resolveBaseUrl(xmlRepresentation, baseUrl);

    // SegmentBase
    NodeList segmentBaseNodes =
        ((org.w3c.dom.Element) xmlRepresentation).getElementsByTagName("SegmentBase");
    if (segmentBaseNodes.getLength() > 0 || hasParentSegmentBase) {
      List<URI> segments = new ArrayList<>();
      segments.add(baseUrl);
      return new Representation(
          segments, width, height, codec, bandwidth, periodDuration, mimeType, lang);
    }

    // SegmentList
    Node xmlSegmentList = XmlUtil.getFirstTag(xmlRepresentation, "SegmentList");
    if (xmlSegmentList != null) {
      return parseSegmentList(
          xmlSegmentList, baseUrl, width, height, codec, bandwidth, periodDuration, mimeType, lang);
    }

    // SegmentTemplate
    Node xmlSegmentTemplate = XmlUtil.findSelfOrParentTag(xmlRepresentation, "SegmentTemplate");
    if (xmlSegmentTemplate != null) {
      Node xmlSegmentTimeline = XmlUtil.getFirstTag(xmlSegmentTemplate, "SegmentTimeline");
      if (xmlSegmentTimeline == null) {
        // simple addressing
        return parseSegmentTimeLineSimple(
            xmlSegmentTemplate,
            attrs,
            baseUrl,
            width,
            height,
            codec,
            bandwidth,
            bandwidthStr,
            periodDuration,
            mimeType,
            lang);
      }
      // explicit addressing
      NodeList xmlSs = ((org.w3c.dom.Element) xmlSegmentTimeline).getElementsByTagName("S");
      if (xmlSs.getLength() > 0) {
        return parseSegmentTimeLineExplicit(
            xmlSegmentTemplate,
            xmlSs,
            attrs,
            baseUrl,
            width,
            height,
            codec,
            bandwidth,
            bandwidthStr,
            periodDuration,
            mimeType,
            lang);
      }
    }
    return null;
  }
}
