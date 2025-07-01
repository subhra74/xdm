package xdm.core.media.parser.dash;

import java.net.URI;
import java.util.List;
import lombok.Getter;
import lombok.ToString;

@ToString
@Getter
public class Representation {
  private final int width;
  private final int height;
  private final String codec;
  private final long bandwidth;
  private final long duration;
  @ToString.Exclude private final List<URI> segments;
  private final String mimeType;
  private final String language;

  public Representation(
      List<URI> segments,
      int width,
      int height,
      String codec,
      long bandwidth,
      long duration,
      String mimeType,
      String language) {
    this.segments = segments;
    this.width = width;
    this.height = height;
    this.codec = codec;
    this.bandwidth = bandwidth;
    this.duration = duration;
    this.mimeType = mimeType;
    this.language = language;
  }
}
