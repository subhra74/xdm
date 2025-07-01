package xdm.app.services.core;

import xdm.app.models.AppConfig;

public class AppConfigService {
  private final AppConfig config = new AppConfig();
  private boolean configLoaded = false;

  private AppConfigService() {}

  public static final AppConfigService INSTANCE = new AppConfigService();

  public synchronized AppConfig getConfig() {
    if (!configLoaded) {
      loadConfig();
      configLoaded = true;
    }
    return config;
  }

  private void loadConfig() {}
}
