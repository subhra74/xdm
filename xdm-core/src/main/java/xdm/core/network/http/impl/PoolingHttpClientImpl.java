package xdm.core.network.http.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.Channels;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import xdm.core.network.http.*;
import xdm.core.network.http.HttpResponse;
import xdm.core.util.StringUtils;

public class PoolingHttpClientImpl implements PoolingHttpClient {
  private final CloseableHttpClient hc;
  private final PoolingHttpClientConnectionManager cm;

  public PoolingHttpClientImpl(int poolSize) {
    this.cm =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(poolSize)
            .setMaxConnTotal(2 * poolSize)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setSocketTimeout(30, TimeUnit.SECONDS)
                    .setConnectTimeout(30, TimeUnit.SECONDS)
                    .build())
            .build();
    this.hc =
        HttpClients.custom()
            .setConnectionManager(cm)
            .disableAutomaticRetries()
            .disableContentCompression()
            .evictExpiredConnections()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setHardCancellationEnabled(true)
                    .setConnectionRequestTimeout(30, TimeUnit.SECONDS)
                    .setResponseTimeout(30, TimeUnit.SECONDS)
                    .build())
            .evictIdleConnections(TimeValue.ofSeconds(3))
            .build();
  }

  @Override
  public void close() {
    this.hc.close(CloseMode.IMMEDIATE);
    this.cm.close(CloseMode.IMMEDIATE);
  }

  @Override
  public HttpResponse getResponse(String url, HeaderCollection headers, String cookie, Range range)
      throws IOException {
    HttpGet httpGet = new HttpGet(url);
    HttpClientContext context = new HttpClientContext();
    Set<String> cookies = new LinkedHashSet<>();

    if (headers != null) {
      for (Iterator<HttpHeader> it = headers.getAll(); it.hasNext(); ) {
        HttpHeader header = it.next();
        if (header.getName().toLowerCase(Locale.ENGLISH).equals("cookie")) {
          cookies.add(header.getValue());
          continue;
        }
        httpGet.addHeader(header.getName(), header.getValue());
      }
    }

    if (range != null) {
      if (range.getEnd() <= 0) {
        httpGet.addHeader(HttpHeaders.RANGE, String.format("bytes=%d-", range.getStart()));
      } else {
        httpGet.addHeader(
            HttpHeaders.RANGE, String.format("bytes=%d-%d", range.getStart(), range.getEnd()));
      }
    } else {
      httpGet.addHeader(HttpHeaders.RANGE, "bytes=0-");
    }

    if (!StringUtils.isNullOrEmpty(cookie)) {
      cookies.add(cookie);
    }

    if (!cookies.isEmpty()) {
      httpGet.addHeader(HttpHeaders.COOKIE, String.join(";", cookies));
    }

    final ClassicHttpResponse response = this.hc.executeOpen(null, httpGet, context);
    final HttpEntity responseEntity = response.getEntity();
    final InputStream inputStream = responseEntity.getContent();
    final RedirectLocations locations = context.getRedirectLocations();
    URI finalUrl = null;
    boolean isRedirected = false;
    if (locations.size() > 0) {
      finalUrl = locations.get(locations.size() - 1);
      isRedirected = true;
    }
//    return HttpResponseImpl.builder()
//        .statusCode(response.getCode())
//        .statusMessage(response.getReasonPhrase())
//        .contentLength(
//            Optional.ofNullable(responseEntity).map(EntityDetails::getContentLength).orElse(-1L))
//        .contentType(
//            Optional.ofNullable(responseEntity).map(EntityDetails::getContentType).orElse(null))
//        .contentDisposition(
//            Optional.ofNullable(response.getFirstHeader(HttpHeaders.CONTENT_DISPOSITION))
//                .map(NameValuePair::getValue)
//                .orElse(null))
//        .lastModified(LocalDateTime.now())
//        .inputStream(inputStream)
//        .finalUrl(finalUrl)
//        .isRedirected(isRedirected)
//        .closeCallback(
//            () -> {
//              try {
//                inputStream.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//              try {
//                responseEntity.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//              try {
//                response.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//            })
//        .headerCallback(
//            name ->
//                Optional.ofNullable(response.getFirstHeader(name))
//                    .map(NameValuePair::getValue)
//                    .orElse(null))
//        .build();
    return null;
  }
}
