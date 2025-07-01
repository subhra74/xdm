package xdm.core.downloaders.metadata;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class HlsDownloadMetadata extends MultiSourceDownloadMetadata {
  private String audioUrl;
  private String videoUrl;
}
