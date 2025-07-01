package xdm.app.models;

import lombok.Builder;
import lombok.Data;
import xdm.core.downloaders.AbstractDownloader;

@Data
@Builder
public class DownloaderHolder {
  private AbstractDownloader downloader;
  private boolean isNonInteractive;
}
