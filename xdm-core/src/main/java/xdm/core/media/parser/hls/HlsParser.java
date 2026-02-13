//package xdm.core.media.parser.hls;
//
//import java.net.URI;
//import java.util.*;
//import java.util.stream.StreamSupport;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import xdm.core.media.parser.util.UrlResolver;
//import xdm.core.util.StringUtils;
//
//public class HlsParser {
//  private static final Logger logger = LoggerFactory.getLogger(HlsParser.class);
//  public static final String AUDIO = "AUDIO";
//  public static final String VIDEO = "VIDEO";
//  public static final String EXT_X_STREAM_INF = "#EXT-X-STREAM-INF:";
//  public static final String EXT_X_MEDIA = "#EXT-X-MEDIA:";
//  public static final String EXT_X_BYTERANGE = "#EXT-X-BYTERANGE:";
//  public static final String EXTINF = "#EXTINF:";
//  public static final String EXT_X_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:";
//  public static final String EXT_X_KEY = "#EXT-X-KEY:";
//  public static final String EXT_X_MAP = "#EXT-X-MAP:";
//  public static final String EXT_X_VERSION = "#EXT-X-VERSION:";
//  public static final String EXT_X_I_FRAMES_ONLY = "#EXT-X-I-FRAMES-ONLY:";
//  public static final String METHOD = "METHOD";
//
//  private HlsParser() {}
//
//  public static boolean isMasterPlaylist(List<String> manifestLines) {
//    return manifestLines.stream()
//        .anyMatch(s -> StringUtils.containsIgnoreCase(s, EXT_X_STREAM_INF));
//  }
//
//  public static HlsMediaPlaylist parseMediaSegments(
//      Iterable<String> manifestLines, String playlistUrl) throws Exception {
//    List<HlsMediaSegment> mediaSegments = new ArrayList<>();
//    long mediaSequence = 0;
//    long startOffset = 0;
//    long segmentLength = 0;
//    long segmentEndOffset = 0;
//    double duration = 0.0;
//    double totalDuration = 0.0;
//    boolean hasByteRange = false;
//    boolean isEncrypted = false;
//    boolean hasInitMap = false;
//    URI baseUrl = URI.create(playlistUrl);
//    boolean keyFrameOnly = false;
//    boolean missingIv = true;
//    int version = -1;
//
//    URI keyUrl = null;
//    String iv = null;
//
//    boolean sigFound = false;
//
//    for (String lineText : manifestLines) {
//      String line = lineText.trim();
//      if (line.isEmpty()) continue;
//
//      if (!sigFound) {
//        if (line.startsWith("#EXTM3U")) {
//          sigFound = true;
//          continue;
//        } else {
//          return null;
//        }
//      }
//
//      if (line.charAt(0) != '#') {
//        if (isEncrypted && missingIv) {
//          iv = HlsHelper.toBigEndian128BitHex(mediaSequence);
//        }
//        HlsMediaSegment segment =
//            HlsMediaSegment.builder()
//                .url(UrlResolver.resolve(baseUrl, line))
//                .byteRange(new AbstractMap.SimpleEntry<>(startOffset, segmentLength))
//                .duration(duration)
//                .keyUrl(keyUrl)
//                .iv(iv)
//                .build();
//        mediaSegments.add(segment);
//        mediaSequence++;
//        totalDuration += duration;
//      } else if (line.startsWith(EXT_X_I_FRAMES_ONLY)) {
//        keyFrameOnly = true;
//      } else if (line.startsWith(EXT_X_VERSION)) {
//        version = Integer.parseInt(line.substring(EXT_X_VERSION.length()).trim());
//      } else if (line.startsWith(EXT_X_BYTERANGE)) {
//        hasByteRange = true;
//        String attrList = line.substring(EXT_X_BYTERANGE.length()).trim();
//        AbstractMap.SimpleEntry<Long, Long> kv = parseByteRange(attrList);
//        long offset = kv.getKey();
//        long length = kv.getValue();
//        if (offset > 0) {
//          startOffset = offset;
//        } else {
//          startOffset = segmentEndOffset;
//        }
//        segmentLength = length;
//        segmentEndOffset += segmentLength;
//      } else if (line.startsWith(EXTINF)) {
//        String attrs = line.substring(EXTINF.length()).trim();
//        if (!attrs.isEmpty()) {
//          duration = Double.parseDouble(attrs.split(",")[0]);
//        }
//      } else if (line.startsWith(EXT_X_MEDIA_SEQUENCE)) {
//        mediaSequence = Integer.parseInt(line.substring(EXT_X_MEDIA_SEQUENCE.length()).trim());
//      } else if (line.startsWith(EXT_X_KEY)) {
//        isEncrypted = true;
//        Map<String, String> attributes =
//            HlsHelper.parseAttributes(line.substring(EXT_X_KEY.length()));
//        if (attributes.containsKey(METHOD)) {
//          if ("NONE".equalsIgnoreCase(attributes.get(METHOD))) {
//            isEncrypted = false;
//            continue;
//          }
//          if ("AES-128".equalsIgnoreCase(attributes.get(METHOD))
//              && "identity".equalsIgnoreCase(attributes.getOrDefault("KEYFORMAT", "identity"))) {
//            keyUrl = UrlResolver.resolve(baseUrl, attributes.get("URI"));
//            if (!attributes.containsKey("IV")) {
//              iv = null;
//              missingIv = true;
//            } else {
//              iv = attributes.get("IV");
//              missingIv = false;
//            }
//          }
//        }
//      } else if (line.startsWith(EXT_X_MAP)) {
//        Map<String, String> attributes =
//            HlsHelper.parseAttributes(line.substring(EXT_X_MAP.length()));
//        if (attributes.containsKey("URI")) {
//          hasInitMap = true;
//          HlsMediaSegment segment =
//              HlsMediaSegment.builder()
//                  .url(UrlResolver.resolve(baseUrl, attributes.get("URI")))
//                  .byteRange(
//                      attributes.containsKey("BYTERANGE")
//                          ? parseByteRange(attributes.get("BYTERANGE"))
//                          : new AbstractMap.SimpleEntry<>(0L, 0L))
//                  .duration(0)
//                  .keyUrl(keyUrl)
//                  .iv(iv)
//                  .build();
//          mediaSegments.add(segment);
//        }
//      }
//    }
//
//    if (!mediaSegments.isEmpty()) {
//      HlsMediaPlaylist playlist = new HlsMediaPlaylist();
//      playlist.setVersion(version);
//      playlist.setMediaSegments(mediaSegments);
//      playlist.setEncrypted(isEncrypted);
//      playlist.setHasByteRange(hasByteRange);
//      playlist.setTotalDuration(totalDuration);
//      playlist.setKeyFrameOnly(keyFrameOnly);
//      playlist.setHasInitSection(hasInitMap);
//      return playlist;
//    }
//    return null;
//  }
//
//  public static List<HlsMasterPlaylist> parseMasterPlaylist(
//      List<String> manifestLines, String playlistUrl) throws Exception {
//    List<Map<String, String>> mapExtStreamInf = new ArrayList<>();
//    List<Map<String, String>> mapExtMedia = new ArrayList<>();
//    List<HlsMasterPlaylist> containers = new ArrayList<>();
//    URI baseUrl = URI.create(playlistUrl);
//    boolean sigFound = false;
//
//    List<URI> urls = new ArrayList<>();
//    for (String lineText : manifestLines) {
//      String line = lineText.trim();
//      if (line.isEmpty()) continue;
//
//      if (!sigFound) {
//        if (line.startsWith("#EXTM3U")) {
//          sigFound = true;
//          continue;
//        } else {
//          logger.error("Invalid HLS manifest, header signature not found!");
//          return null;
//        }
//      }
//
//      if (line.charAt(0) != '#') {
//        urls.add(UrlResolver.resolve(baseUrl, line));
//      } else if (line.startsWith(EXT_X_STREAM_INF)) {
//        mapExtStreamInf.add(HlsHelper.parseAttributes(line.substring(EXT_X_STREAM_INF.length())));
//      } else if (line.startsWith(EXT_X_MEDIA)) {
//        mapExtMedia.add(HlsHelper.parseAttributes(line.substring(EXT_X_MEDIA.length())));
//      }
//    }
//
//    if (mapExtStreamInf.isEmpty()) {
//      logger.error("No attribute in stream info, can't parse HLS manifest");
//      return null;
//    }
//
//    for (int i = 0; i < urls.size(); i++) {
//      Map<String, String> extStreamInf = mapExtStreamInf.get(i);
//      String codecs = extStreamInf.get("CODECS");
//      if (!StringUtils.isNullOrEmpty(codecs)) {
//        boolean hasVideo = CodecMap.containsVideoCodec(codecs);
//        boolean hasAudio = CodecMap.containsAudioCodec(codecs);
//        if (hasAudio && hasVideo) {
//          containers.add(
//              HlsMasterPlaylist.builder()
//                  .videoPlaylist(urls.get(i))
//                  .attributes(extStreamInf)
//                  .build());
//          continue;
//        }
//        if (hasVideo) {
//          if (extStreamInf.containsKey(AUDIO)) {
//            String groupId = extStreamInf.get(AUDIO);
//            for (Map<String, String> media : mapExtMedia) {
//              if (groupId.equals(media.get("GROUP-ID")) && AUDIO.equals(media.get("TYPE"))) {
//                HlsMasterPlaylist container =
//                    HlsMasterPlaylist.builder()
//                        .videoPlaylist(urls.get(i))
//                        .audioPlaylist(
//                            media.containsKey("URI")
//                                ? UrlResolver.resolve(baseUrl, media.get("URI"))
//                                : null)
//                        .attributes(mergeDict(extStreamInf, media))
//                        .build();
//                containers.add(container);
//              }
//            }
//          } else {
//            logger.warn("Video only stream found!");
//            containers.add(
//                HlsMasterPlaylist.builder()
//                    .videoPlaylist(urls.get(i))
//                    .attributes(extStreamInf)
//                    .build());
//          }
//        }
//        if (hasAudio) {
//          if (extStreamInf.containsKey(VIDEO)) {
//            String groupId = extStreamInf.get(VIDEO);
//            for (Map<String, String> media : mapExtMedia) {
//              if (groupId.equals(media.get("GROUP-ID")) && VIDEO.equals(media.get("TYPE"))) {
//                HlsMasterPlaylist container =
//                    HlsMasterPlaylist.builder()
//                        .videoPlaylist(
//                            media.containsKey("URI")
//                                ? UrlResolver.resolve(baseUrl, media.get("URI"))
//                                : null)
//                        .audioPlaylist(urls.get(i))
//                        .attributes(mergeDict(extStreamInf, media))
//                        .build();
//                containers.add(container);
//              }
//            }
//          } else {
//            logger.warn("Audio only stream found!");
//            containers.add(
//                HlsMasterPlaylist.builder()
//                    .videoPlaylist(urls.get(i))
//                    .attributes(extStreamInf)
//                    .build());
//          }
//        }
//      }
//    }
//
//    return containers;
//  }
//
//  private static Map<String, String> mergeDict(Map<String, String> d1, Map<String, String> d2) {
//    Map<String, String> dict = new HashMap<>(d1);
//    dict.putAll(d2);
//    return dict;
//  }
//
//  private static AbstractMap.SimpleEntry<Long, Long> parseByteRange(String str) {
//    long offset = 0;
//    long length;
//    String[] attrs = str.split("@");
//    length = Long.parseLong(attrs[0]);
//    if (attrs.length == 2) {
//      offset = Long.parseLong(attrs[1]);
//    }
//    return new AbstractMap.SimpleEntry<>(offset, length);
//  }
//}
