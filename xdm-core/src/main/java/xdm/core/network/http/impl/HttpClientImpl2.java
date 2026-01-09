package xdm.core.network.http.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.RedirectLocations;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import xdm.core.network.http.*;
import xdm.core.network.http.HttpResponse;
import xdm.core.util.StringUtils;

public class HttpClientImpl2 implements PoolingHttpClient {
  private final CloseableHttpClient hc;
  private final PoolingHttpClientConnectionManager cm;

  public HttpClientImpl2(int poolSize) {
    this.cm = new PoolingHttpClientConnectionManager();
    cm.setMaxTotal(poolSize);
    cm.setDefaultMaxPerRoute(poolSize);
    this.hc =
        HttpClients.custom()
            .setConnectionManager(cm)
            .disableAutomaticRetries()
            .disableContentCompression()
            .evictExpiredConnections()
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectTimeout(30000)
                    .setConnectionRequestTimeout(30000)
                    .setSocketTimeout(30000)
                    .setRedirectsEnabled(true)
                    .build())
            .evictIdleConnections(5, TimeUnit.SECONDS)
            .build();
  }

  @Override
  public void close() {
    try {
      this.hc.close();
    } catch (IOException e) {
      // No op
    }
    this.cm.shutdown();
    this.cm.close();
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
      httpGet.addHeader("Cookie", String.join(";", cookies));
    }

    final CloseableHttpResponse response = this.hc.execute(httpGet, context);
    final HttpEntity responseEntity = response.getEntity();
    final InputStream inputStream = responseEntity.getContent();
    URI finalUrl = null;
    finalUrl = URI.create(context.getRequest().getRequestLine().getUri());
    boolean isRedirected =
        StringUtils.equalsIgnoreCase(context.getRequest().getRequestLine().getUri(), url);
    final HttpResponseImpl response1 = new HttpResponseImpl();
    response1.setStatusCode(response.getStatusLine().getStatusCode());
    response1.setStatusMessage(response.getStatusLine().getReasonPhrase());
    response1.setContentLength(responseEntity.getContentLength());
    response1.setContentType(
        Optional.ofNullable(responseEntity.getContentType())
            .map(NameValuePair::getValue)
            .orElse(null));
    response1.setContentDisposition(response1.getContentDisposition());
    response1.setInputStream(inputStream);
    response1.setFinalUrl(finalUrl);
    response1.setRedirected(isRedirected);
    response1.setCloseCallback(
        () -> {
          try {
            inputStream.close();
          } catch (Exception ex) {
            // Swallow error
          }
          //          try {
          //            responseEntity.c
          //          } catch (Exception ex) {
          //            // Swallow error
          //          }
          try {
            response.close();
          } catch (Exception ex) {
            // Swallow error
          }
        });
    response1.setHeaderCallback(
        name ->
            Optional.ofNullable(response.getFirstHeader(name))
                .map(NameValuePair::getValue)
                .orElse(null));

    //    return HttpResponseImpl.builder()
    //        .statusCode(response.getCode())
    //        .statusMessage(response.getReasonPhrase())
    //        .contentLength(
    //
    // Optional.ofNullable(responseEntity).map(EntityDetails::getContentLength).orElse(-1L))
    //        .contentType(
    //
    // Optional.ofNullable(responseEntity).map(EntityDetails::getContentType).orElse(null))
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
    return response1;
  }
}
