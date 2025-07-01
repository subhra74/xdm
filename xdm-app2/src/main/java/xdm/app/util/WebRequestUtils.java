package xdm.app.util;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class WebRequestUtils {
  public byte[] get(String url) throws IOException {
    synchronized (this) {
      if (httpClient == null) {
        createHttpClient();
      }
    }
    var request = new HttpGet(url);
    var context = new HttpClientContext();
    try (var res = this.httpClient.executeOpen(null, request, context);
        var in = res.getEntity().getContent()) {
      return in.readAllBytes();
    }
  }

  public byte[] post(String url, byte[] bytes) throws IOException {
    synchronized (this) {
      if (httpClient == null) {
        createHttpClient();
      }
    }
    var request = new HttpPost(url);
    request.setEntity(new ByteArrayEntity(bytes, ContentType.APPLICATION_JSON));
    var context = new HttpClientContext();
    try (var res = this.httpClient.executeOpen(null, request, context);
        var in = res.getEntity().getContent()) {
      return in.readAllBytes();
    }
  }

  private CloseableHttpClient httpClient;

  private void createHttpClient() {
    this.httpClient =
        HttpClients.custom()
            .setConnectionManager(
                PoolingHttpClientConnectionManagerBuilder.create()
                    .setMaxConnPerRoute(5)
                    .setMaxConnTotal(2 * 10)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setSocketTimeout(30, TimeUnit.SECONDS)
                            .setConnectTimeout(30, TimeUnit.SECONDS)
                            .build())
                    .build())
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
}
