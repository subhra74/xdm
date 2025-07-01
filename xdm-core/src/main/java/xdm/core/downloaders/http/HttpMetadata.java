package xdm.core.downloaders.http;

import lombok.*;
import xdm.core.downloaders.Metadata;
import xdm.core.network.http.HeaderCollection;

@EqualsAndHashCode(callSuper = true)
@Data
public class HttpMetadata extends Metadata {
  private String url;

  @Builder
  public HttpMetadata(
      long id,
      String cookies,
      HeaderCollection headers,
      String fileName,
      String contentType,
      boolean autoSelectFolder,
      String folder,
      long dateAdded,
      String originPage,
      String url,
      boolean keepFileName) {
    super(
        id,
        cookies,
        headers,
        fileName,
        contentType,
        autoSelectFolder,
        folder,
        dateAdded,
        originPage,
        keepFileName);
    this.url = url;
  }

  @Override
  public String getPrimaryUrl() {
    return this.url;
  }
}
