package xdm.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import xdm.app.services.ipc.impl.IpcServiceImpl;
import xdm.app.util.InstanceArgsUtils;
import xdm.app.util.WebRequestUtils;

public class AppContext {
  @Getter private static final WebRequestUtils webRequestUtils = new WebRequestUtils();
  private static ObjectMapper mapper;
  private static String[] args;

  private static void onError() {
    InstanceArgsUtils.sendParam(args);
    System.exit(0);
  }

  private static void onSuccess() {}

  public static void start(String[] args) {
    AppContext.args = args;
    IpcServiceImpl.INSTANCE.start(AppContext::onError, AppContext::onSuccess);
  }

  public static synchronized ObjectMapper getObjectMapper() {
    if (mapper == null) {
      mapper = new ObjectMapper();
    }
    return mapper;
  }
}
