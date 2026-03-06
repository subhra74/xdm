package xdm.app.service;

import xdm.app.constants.SortKey;
import xdm.core.downloaders.Metadata;

import java.util.List;

public interface AppConfigService {
  void load();

  boolean shouldShowDownloadCompleteWindow();

  boolean shouldRunVirusScan();

  boolean shouldRunCommand();

  boolean shouldShowDownloadProgressWindow();

  String getDefaultDownloadFolder();

  String getTempFolder();

  boolean shouldAutoRenameOnConflict();

  boolean shouldShutdownAfterAllDone();

  int getMaxParallelDownloads();

  List<String> getRecentFolders();

  boolean isAutoSelectFolder();

  void setAutoSelectFolder(boolean value);

  int getFolderIndex();

  void setFolderIndex(int value);

  SortKey getSortKey();

  void setSortKey(SortKey sortKey);

  boolean isSortAscending();

  void setSortAscending(boolean ascending);

  long getMinVideoSize();
  void setMinVideoSize(long value);
}
