package xdm.core.downloaders.metadata;

import lombok.Data;
import xdm.core.network.http.HeaderCollection;

@Data
public class MultiSourceDownloadMetadata {
  protected String cookies;
  protected HeaderCollection headers;
  protected String file;
  protected String contentType;
}
