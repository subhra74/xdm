package xdm.core.downloaders;

import lombok.*;
import xdm.core.network.http.HeaderCollection;

@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class Metadata {
  protected long id;
  protected String cookies;
  protected HeaderCollection headers;
  protected String fileName;
  protected String contentType;
  protected boolean
      autoSelectFolder; // Download folder is set manually as opposed to category based one
  protected String folder;
  private long dateAdded;
  private String originPage;
  private boolean
      keepFileName; // Change file extension in case of redirection but keep file name same

  public abstract String getPrimaryUrl();
}
