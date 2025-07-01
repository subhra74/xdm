package xdm.core.media.parser.hls;

import xdm.core.util.CollectionUtils;
import xdm.core.util.StringUtils;

import java.util.Set;

public final class CodecMap {
  private CodecMap() {}

  public static boolean containsAudioCodec(String codec) {
    if (StringUtils.isNullOrEmpty(codec)) {
      return false;
    }
    return audioPattern.stream().anyMatch(p -> StringUtils.containsIgnoreCase(codec, p));
  }

  public static boolean containsVideoCodec(String codec) {
    if (StringUtils.isNullOrEmpty(codec)) {
      return false;
    }
    return videoPattern.stream().anyMatch(p -> StringUtils.containsIgnoreCase(codec, p));
  }

  public static final Set<String> videoPattern =
      CollectionUtils.setOf("avc", "dvh", "hev", "hvc", "av0", "dav");
  public static final Set<String> audioPattern = CollectionUtils.setOf("ac", "ec", "mp");
}
