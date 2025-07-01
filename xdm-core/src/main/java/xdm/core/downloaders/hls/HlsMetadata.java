package xdm.core.downloaders.hls;

import lombok.*;
import xdm.core.downloaders.Metadata;

@EqualsAndHashCode(callSuper = true)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HlsMetadata extends Metadata {
  private String audioUrl;
  private String url;

  public boolean hasSeparateAudio() {
    return audioUrl != null;
  }

  @Override
  public String getPrimaryUrl() {
    return this.url;
  }
}
