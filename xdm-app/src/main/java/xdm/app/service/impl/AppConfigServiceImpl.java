package xdm.app.service.impl;

import lombok.Getter;
import lombok.Setter;
import xdm.app.constants.SortKey;
import xdm.app.service.AppConfigService;
import xdm.core.downloaders.Metadata;
import xdman.ui.res.StringResource;

import java.io.File;
import java.util.List;

public class AppConfigServiceImpl implements AppConfigService {
  @Getter @Setter private boolean autoSelectFolder;
  @Getter @Setter private int folderIndex;
  @Getter @Setter private SortKey sortKey = SortKey.DATE;
  @Getter @Setter private boolean sortAscending = false;
  @Getter @Setter private long minVideoSize = 1024;

  @Override
  public void load() {}

  @Override
  public String getFolderForDownload(Metadata metadata) {
    if (metadata.isAutoSelectFolder()) {
      return new File(System.getProperty("user.home", "Downloads")).getAbsolutePath();
    } else {
      return metadata.getFolder();
    }
  }

  @Override
  public boolean shouldShowDownloadCompleteWindow() {
    return true;
  }

  @Override
  public boolean shouldRunVirusScan() {
    return false;
  }

  @Override
  public boolean shouldRunCommand() {
    return false;
  }

  @Override
  public boolean shouldShowDownloadProgressWindow() {
    return true;
  }

  @Override
  public String getDefaultDownloadFolder() {
    return new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
  }

  @Override
  public String getTempFolder() {
    return new File(System.getProperty("user.home"), ".temp").getAbsolutePath();
  }

  @Override
  public boolean shouldAutoRenameOnConflict() {
    return true;
  }

  @Override
  public boolean shouldShutdownAfterAllDone() {
    return false;
  }

  @Override
  public int getMaxParallelDownloads() {
    return 5;
  }

  @Override
  public List<String> getRecentFolders() {
    return List.of(StringResource.get("ND_AUTO_CAT"), getDefaultDownloadFolder());
  }
}
