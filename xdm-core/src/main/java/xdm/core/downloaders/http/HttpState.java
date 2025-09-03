package xdm.core.downloaders.http;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import xdm.core.network.http.HeaderCollection;

import java.util.concurrent.atomic.AtomicLong;

@Getter
@Setter
@Builder
public class HttpState {
  private long id;
  private String url;
  private HeaderCollection headers;
  private String cookie;
  private String fileName;
  private String folder;
  private boolean autoSelectFolder;
  private AtomicLong fileSize;
  private AtomicLong downloaded;
  private String serverTimeStamp;
  private String tempFolder;
}
