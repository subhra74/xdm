package xdm.app.service;

public interface PlatformService {
  void runVirusScan(String file);

  void runCustomCommand(String file);

  void shutdownPC();
}
