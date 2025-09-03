package xdm.core.media.parser.hls;

import java.net.URI;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@Builder
@ToString
public class HlsMasterPlaylist {
  private URI videoPlaylist;
  private URI audioPlaylist;
  private Map<String, String> attributes;

  public String getQuality() {
    if (attributes == null) return null;
    String resolution = attributes.get("RESOLUTION");
    String bandwidth = attributes.get("BANDWIDTH");
    String name = attributes.get("NAME");
    String lang = attributes.get("LANGUAGE");
    StringBuilder text = new StringBuilder();
    if (resolution != null) {
      text.append(resolution);
    }
    if (bandwidth != null) {
      try {
        if (text.length() > 0) text.append(" ");
        text.append((Long.parseLong(bandwidth) / 1024)).append(" kbps");
      } catch (Exception e) {
        // ignore
      }
    }
    if (name != null) {
      if (text.length() > 0) text.append(" ");
      text.append(name);
      if (lang != null) {
        text.append(" ").append(lang);
      }
    }
    return text.length() > 0 ? text.toString() : null;
  }
}
