package xdm.core;

import xdm.core.constants.ErrorCode;

public interface DownloadProgressListener {
  public void downloadFinished(long id);

  public void downloadFailed(long id, ErrorCode error);

  public void downloadStopped(long id);

  public void downloadConfirmed(long id);

  public void downloadUpdated(long id);

  public String getOutputFolder(long id);

  public String getOutputFileName(long id);
}
