package xdm.core.network.http.impl;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.function.Function;
import lombok.Builder;
import lombok.Getter;
import xdm.core.network.http.HttpResponse;

@Getter
@Builder
public class HttpResponseImpl implements HttpResponse {

  //  public HttpResponseImpl(ClassicHttpResponse response, HttpClientContext context) {
  //    this.response = response;
  //    this.responseEntity = response.getEntity();
  //    this.inputStream=responseEntity.getContent();
  //    RedirectLocations locations = context.getRedirectLocations();
  //    if (locations.size() > 0) {
  //      this.finalUrl = locations.get(locations.size() - 1);
  //      this.isRedirected = true;
  //    } else {
  //      finalUrl = null;
  //    }
  //  }

  @Override
  public void close() {
    this.closeCallback.run();
  }

  @Override
  public String getHeader(String name) {
    return this.headerCallback.apply(name);
  }

  private Runnable closeCallback;
  private Function<String, String> headerCallback;
  private String statusMessage;
  private String contentDisposition;
  private String contentType;
  private int statusCode;
  private long contentLength;
  private LocalDateTime lastModified;
  private InputStream inputStream;
  private URI finalUrl;
  private boolean isRedirected;
}
