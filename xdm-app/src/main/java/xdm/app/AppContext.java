package xdm.app;

import lombok.Data;
import xdm.app.service.*;

@Data
public class AppContext {
  public static final AppContext INSTANCE = new AppContext();

  private AppContext() {}

  private DownloadsDBService downloadsDbService;
  private AppControllerService appControllerService;
  private DownloadsControllerService downloadsControllerService;
  private AppConfigService configService;
  private PlatformService platformService;
  private QueueService queueService;
  private RpcService rpcService;

  public void start(final String[] args) {
    configService.load();
    rpcService.start(
        () -> {
          downloadsDbService.load();
          appControllerService.run(args);
        },
        err -> {});
  }
}
