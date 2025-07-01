package xdm.core.network.http;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;

public interface HttpResponse extends AutoCloseable {
  void close();

  int getStatusCode();

  String getStatusMessage();

  long getContentLength();

  String getContentDisposition();

  String getContentType();

  LocalDateTime getLastModified();

  InputStream getInputStream();

  boolean isRedirected();

  URI getFinalUrl();

  String getHeader(String name);
}
