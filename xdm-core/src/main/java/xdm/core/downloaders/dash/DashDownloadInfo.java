package xdm.core.downloaders.dash;

import lombok.Builder;
import lombok.Data;
import xdm.core.media.parser.dash.MpdEntry;
import xdm.core.network.http.HeaderCollection;

@Data
@Builder
public class DashDownloadInfo {
  private String id;
  private MpdEntry mpdEntry;
  private HeaderCollection headers;
  private String contentType;
  private String originPage;
}
