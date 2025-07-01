package xdm.core.network.http;

import java.io.IOException;

public interface PoolingHttpClient {
  void close();

  HttpResponse get(String url, HeaderCollection headers, String cookie, Range range) throws IOException;
}
