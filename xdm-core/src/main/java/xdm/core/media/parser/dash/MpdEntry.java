package xdm.core.media.parser.dash;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@ToString
@Builder
@AllArgsConstructor
public class MpdEntry {
  private Representation video;
  private Representation audio;
}
