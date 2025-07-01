package xdm.core.media.parser.hls;

import static java.util.AbstractMap.SimpleEntry;

import java.net.URI;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Builder
@Data
@ToString
public class HlsMediaSegment {
  private URI url;
  private SimpleEntry<Long, Long> byteRange;
  private double duration;
  private URI keyUrl;
  private String iv;
}
