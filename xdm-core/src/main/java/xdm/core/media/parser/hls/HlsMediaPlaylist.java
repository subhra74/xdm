package xdm.core.media.parser.hls;

import java.util.List;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class HlsMediaPlaylist {
  private List<HlsMediaSegment> mediaSegments;
  private boolean encrypted;
  private boolean hasByteRange;
  private double totalDuration;
  private boolean keyFrameOnly;
  private boolean hasInitSection;
  private int version;
}
