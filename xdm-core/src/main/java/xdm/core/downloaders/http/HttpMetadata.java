package xdm.core.downloaders.http;

import lombok.*;
import xdm.core.downloaders.Metadata;
import xdm.core.network.http.HeaderCollection;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static xdm.core.util.SerializationUtils.readStr;
import static xdm.core.util.SerializationUtils.writeNullable;

@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
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

  @Override
  public synchronized void save(DataOutputStream outputStream) throws IOException {
    super.save(outputStream);
    writeNullable(this.url, outputStream);
  }

  @Override
  public synchronized void read(DataInputStream inputStream) throws IOException {
    super.read(inputStream);
    this.url = readStr(inputStream);
  }
}
