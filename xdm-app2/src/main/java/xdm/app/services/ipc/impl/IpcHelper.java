package xdm.app.services.ipc.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import xdm.app.constants.AppConstants;
import xdm.app.services.core.AppService;
import xdm.app.models.IpcMessage;
import xdm.app.models.IpcResponse;
import xdm.core.Config;
import xdm.core.network.http.HeaderCollection;
import xdm.core.util.CollectionUtils;
import xdm.core.util.FileUtils;
import xdm.monitoring.Request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

public class IpcHelper {
  private IpcHelper() {}

  public static void onDownload(ObjectMapper mapper, Request request, IpcResponse res)
      throws IOException {
    try {
      var message = mapper.readValue(request.getBody(), IpcMessage.class);
      message.setFile(FileUtils.sanitizeFileName(message.getFile()));
      removeBlockedHeaders(message);
      AppService.INSTANCE.addDownload(message);
    } finally {
      setResponseOk(res);
    }
  }

  public static void onSync(ObjectMapper mapper, IpcResponse res) throws IOException {
    byte[] b = getConfigJson(mapper);
    res.setBody(b);
    setResponseOk(res);
  }

  public static void setResponseOk(IpcResponse res) {
    res.setCode(200);
    res.setMessage("OK");
    HeaderCollection headers = new HeaderCollection();
    headers.setValue(AppConstants.CONTENT_TYPE, "application/json");
    headers.setValue(AppConstants.CACHE_CONTROL, "max-age=0, no-cache, no-store,  must-revalidate");
    headers.setValue(AppConstants.PRAGMA, "no-cache");
    headers.setValue(AppConstants.EXPIRES, "0");
    res.setHeaders(headers);
  }

  private static synchronized byte[] getConfigJson(ObjectMapper objectMapper) throws IOException {
    if (configJson == null) {
      configJson = objectMapper.writeValueAsBytes(Config.getInstance());
    }
    return configJson;
  }

  private static byte[] configJson;
  private static final Set<String> blockedHeaders =
      CollectionUtils.setOf(
          "accept",
          "if",
          "authorization",
          "proxy",
          "connection",
          "expect",
          "te",
          "upgrade",
          "range",
          "cookie",
          "transfer-encoding",
          "content-type",
          "content-length",
          "content-encoding");

  private static void removeBlockedHeaders(IpcMessage message) {
    var headers = message.getRequestHeaders();
    if (headers == null) return;
    var keysToRemove = new ArrayList<String>();
    for (String headerName : headers.keySet()) {
      if (blockedHeaders.contains(headerName.toLowerCase(Locale.ENGLISH))) {
        keysToRemove.add(headerName);
      }
    }
    for (String headerName : keysToRemove) {
      headers.remove(headerName);
    }
  }
}
