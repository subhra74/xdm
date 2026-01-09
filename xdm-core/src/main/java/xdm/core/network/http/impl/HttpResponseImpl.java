package xdm.core.network.http.impl;

import java.io.InputStream;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.function.Function;
import lombok.Builder;
import lombok.Getter;
import xdm.core.network.http.HttpResponse;


public class HttpResponseImpl implements HttpResponse {

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

  public Runnable getCloseCallback() {
    return closeCallback;
  }

  public void setCloseCallback(Runnable closeCallback) {
    this.closeCallback = closeCallback;
  }

  @Override
  public String getContentDisposition() {
    return contentDisposition;
  }

  public void setContentDisposition(String contentDisposition) {
    this.contentDisposition = contentDisposition;
  }

  @Override
  public long getContentLength() {
    return contentLength;
  }

  public void setContentLength(long contentLength) {
    this.contentLength = contentLength;
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  @Override
  public URI getFinalUrl() {
    return finalUrl;
  }

  public void setFinalUrl(URI finalUrl) {
    this.finalUrl = finalUrl;
  }

  public Function<String, String> getHeaderCallback() {
    return headerCallback;
  }

  public void setHeaderCallback(Function<String, String> headerCallback) {
    this.headerCallback = headerCallback;
  }

  @Override
  public InputStream getInputStream() {
    return inputStream;
  }

  public void setInputStream(InputStream inputStream) {
    this.inputStream = inputStream;
  }

  @Override
  public boolean isRedirected() {
    return isRedirected;
  }

  public void setRedirected(boolean redirected) {
    isRedirected = redirected;
  }

  @Override
  public LocalDateTime getLastModified() {
    return lastModified;
  }

  public void setLastModified(LocalDateTime lastModified) {
    this.lastModified = lastModified;
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  public void setStatusCode(int statusCode) {
    this.statusCode = statusCode;
  }

  @Override
  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }
}
