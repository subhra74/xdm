//package xdm.core.network.http.impl;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.net.URI;
//import java.time.LocalDateTime;
//import java.util.*;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.TimeUnit;
//
//import okhttp3.*;
//import org.apache.hc.client5.http.classic.methods.HttpGet;
//import org.apache.hc.client5.http.config.ConnectionConfig;
//import org.apache.hc.client5.http.config.RequestConfig;
//import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
//import org.apache.hc.client5.http.impl.classic.HttpClients;
//import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
//import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
//import org.apache.hc.client5.http.protocol.HttpClientContext;
//import org.apache.hc.client5.http.protocol.RedirectLocations;
//import org.apache.hc.core5.http.*;
//import org.apache.hc.core5.io.CloseMode;
//import org.apache.hc.core5.util.TimeValue;
//import xdm.core.network.http.*;
//import xdm.core.network.http.HttpResponse;
//import xdm.core.util.StringUtils;
//
//public class OkHttpClientImpl implements PoolingHttpClient {
//  private final ConnectionPool connectionPool;
//  private final Dispatcher dispatcher;
//  private final OkHttpClient client;
//
//  public OkHttpClientImpl(int poolSize) {
//    dispatcher = new Dispatcher();
//    dispatcher.setMaxRequests(poolSize);
//    dispatcher.setMaxRequestsPerHost(poolSize);
//
//    connectionPool =
//        new ConnectionPool(
//            poolSize, // max idle connections
//            5,
//            TimeUnit.SECONDS // keep-alive duration
//            );
//
//    client =
//        new OkHttpClient.Builder()
//            .dispatcher(dispatcher)
//            .connectionPool(connectionPool)
//            .connectTimeout(30, TimeUnit.SECONDS)
//            .readTimeout(0, TimeUnit.SECONDS) // unlimited for large files
//            .retryOnConnectionFailure(false)
//            .build();
//  }
//
//  @Override
//  public void close() {
//    this.client.dispatcher().cancelAll();
//    ExecutorService exc = dispatcher.executorService();
//    exc.shutdownNow();
//    connectionPool.evictAll();
//  }
//
//  @Override
//  public HttpResponse get(String url, HeaderCollection headers, String cookie, Range range)
//      throws IOException {
//    Request.Builder requestBuilder = new Request.Builder().url(url).get();
//    Set<String> cookies = new LinkedHashSet<>();
//
//    if (headers != null) {
//      for (Iterator<HttpHeader> it = headers.getAll(); it.hasNext(); ) {
//        HttpHeader header = it.next();
//        if (header.getName().toLowerCase(Locale.ENGLISH).equals("cookie")) {
//          cookies.add(header.getValue());
//          continue;
//        }
//        requestBuilder.addHeader(header.getName(), header.getValue());
//      }
//    }
//
//    if (range != null) {
//      if (range.getEnd() <= 0) {
//        requestBuilder.addHeader(HttpHeaders.RANGE, String.format("bytes=%d-", range.getStart()));
//      } else {
//        requestBuilder.addHeader(
//            HttpHeaders.RANGE, String.format("bytes=%d-%d", range.getStart(), range.getEnd()));
//      }
//    } else {
//      requestBuilder.addHeader(HttpHeaders.RANGE, "bytes=0-");
//    }
//
//    if (!StringUtils.isNullOrEmpty(cookie)) {
//      cookies.add(cookie);
//    }
//
//    if (!cookies.isEmpty()) {
//      requestBuilder.addHeader(HttpHeaders.COOKIE, String.join(";", cookies));
//    }
//    final Request request = requestBuilder.build();
//    final Response response = client.newCall(request).execute();
//    final ResponseBody body = response.body();
//    final InputStream inputStream = body.byteStream();
//
//    URI finalUrl = response.request().url().uri();
//    boolean isRedirected = response.priorResponse().isRedirect();
//
//    return HttpResponseImpl.builder()
//        .statusCode(response.code())
//        .statusMessage(response.message())
//        .contentLength(body.contentLength())
//        // .contentType(body.contentType().type())
//        //        .contentDisposition(
//        //            Optional.ofNullable(response.getFirstHeader(HttpHeaders.CONTENT_DISPOSITION))
//        //                .map(NameValuePair::getValue)
//        //                .orElse(null))
//        .lastModified(LocalDateTime.now())
//        .inputStream(inputStream)
//        //        .finalUrl(finalUrl)
//        //        .isRedirected(isRedirected)
//        .closeCallback(
//            () -> {
//              try {
//                inputStream.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//              try {
//                body.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//              try {
//                response.close();
//              } catch (Exception ex) {
//                // Swallow error
//              }
//            })
//        .headerCallback(name -> response.header(name))
//        .build();
//  }
//}
