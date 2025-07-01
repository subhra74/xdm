package xdm.app.services.core;

import xdm.app.models.IpcMessage;

public class AppService {
  private AppService() {}

  public static final AppService INSTANCE = new AppService();

  public void addDownload(IpcMessage message) {}
}
