package xdm.app.util;

import xdm.app.constants.AppConstants;
import xdm.app.AppContext;

import java.io.IOException;

public class InstanceArgsUtils {
  private InstanceArgsUtils() {}

  public static void sendParam(String[] args) {
    try {
      var mapper = AppContext.getObjectMapper();
      var json =
          mapper.writeValueAsBytes(
              args == null || args.length < 1 ? new String[] {AppConstants.RESTORE_WINDOW} : args);
      AppContext.getWebRequestUtils().post(AppConstants.INSTANCE_URL, json);
    } catch (IOException ex) {
      // No point of catching any error
    }
  }
}
