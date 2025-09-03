package xdm.core.downloaders.http;

import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.XDMConstants;
import xdm.core.constants.ErrorCode;
import xdm.core.downloaders.AbstractChunkRetriever;
import xdm.core.downloaders.Chunk;
import xdm.core.network.http.HeaderCollection;
import xdm.core.network.http.HttpResponse;
import xdm.core.network.http.PoolingHttpClient;
import xdm.core.network.http.Range;
import xdm.core.util.XDMUtils;

public class HttpChunkRetriever extends AbstractChunkRetriever {
  private static final Logger logger = LoggerFactory.getLogger(HttpChunkRetriever.class);
  protected String url;
  protected HeaderCollection headers;
  protected InputStream in;
  protected long firstLength;
  protected long totalLength;
  protected boolean redirected;
  protected String redirectUrl;
  private PoolingHttpClient httpClient;
  private HttpResponse response;
  private long actualRange;
  private final String cookie;

  public HttpChunkRetriever(
      Chunk chunk,
      String url,
      HeaderCollection headers,
      String cookie,
      long totalLength,
      PoolingHttpClient httpClient) {
    super(chunk);
    this.url = url;
    this.headers = headers;
    this.cookie = cookie;
    this.totalLength = totalLength;
    this.httpClient = httpClient;
  }

  private boolean checkAndOpenStreams() {
    if (chunk.getLength() < 0 && chunk.getDownloaded() > 0) {
      errorCode = ErrorCode.ERR_NO_RESUME;
      closeImpl();
      logger.error("server does not support resuming");
      return false;
    }
    try {
      chunk.reopenStream();
    } catch (IOException e) {
      logger.error(e.getMessage(), e);
      closeImpl();
      errorCode = ErrorCode.ERR_NO_RESUME;
      return false;
    }
    return true;
  }

  private boolean checkStatusCode(int code) {
    if (code != 200
        && code != 206
        && code != 416
        && code != 413
        && code != 408
        && code != 502
        && code != 503
        && code != 504) {
      logger.error("{}: Invalid response from server {}", chunk.getId(), code);
      errorCode = ErrorCode.ERR_INVALID_RESP;
      return false;
    }
    if (((chunk.getDownloaded() + chunk.getStartOffset()) > 0) && code != 206) {
      logger.error("{}: Server does not support resume {}", chunk.getId(), code);
      errorCode = ErrorCode.ERR_NO_RESUME;
      return false;
    }
    return true;
  }

  private Range makeRange(long length, long expectedLength, long startOff, long endOff) {
    if (length > 0 && expectedLength > 0) {
      logger.info("{} requesting:- Range: bytes={}-{}", chunk, startOff, (endOff - 1));
      return Range.builder().start(startOff).end(endOff - 1).build();
    }
    return null;
  }

  private boolean processResponse(long length, long expectedLength, long startOff) {
    int code = response.getStatusCode();
    if (!checkStatusCode(code)) {
      return false;
    }
    firstLength = response.getContentLength();
    if (length > 0) {
      if (firstLength != expectedLength) {
        logger.error(
            "{}: length mismatch: expected: {} got: {}",
            chunk.getId(),
            expectedLength,
            firstLength);
        errorCode = ErrorCode.ERR_NO_RESUME;
        return false;
      }
      actualRange = startOff + expectedLength;
    }

    if (response.getContentLength() > 0
        && XDMUtils.getFreeSpace(null) < response.getContentLength()) {
      logger.error("{}: Disk is full", chunk.getId());
      errorCode = ErrorCode.DISK_FAILURE;
      return false;
    }

    in = response.getInputStream();
    redirected = this.response.isRedirected();
    if (redirected && this.response.getFinalUrl() != null) {
      redirectUrl = this.response.getFinalUrl().toString();
    }
    logger.error("{}: Connection success", chunk.getId());
    return true;
  }

  @Override
  protected boolean connectImpl() {
    boolean close = true;
    if (stop.get()) {
      closeImpl();
      return false;
    }

    if (!checkAndOpenStreams()) {
      return false;
    }

    while (!stop.get()) {
      try {
        logger.info("{}: Connecting to: {}", chunk.getId(), url);
        long length = chunk.getLength();
        long startOff = chunk.getStartOffset() + chunk.getDownloaded();
        long endOff = startOff + length - chunk.getDownloaded();
        long expectedLength = endOff - startOff;
        if (startOff > 0 && expectedLength < 1) {
          return false;
        }
        Range range = makeRange(length, expectedLength, startOff, endOff);
        this.response = this.httpClient.get(this.url, this.headers, this.cookie, range);
        if (stop.get()) {
          return false;
        }
        if (processResponse(length, expectedLength, startOff)) {
          close = false;
          return true;
        } else {
          return false;
        }
      } catch (Exception ex) {
        logger.error(ex.getMessage(), ex);
        logger.error("{}: Error in connect...", chunk.getId(), ex);
      } finally {
        if (close) {
          closeImpl();
        }
      }
      try {
        Thread.sleep(5000);
      } catch (InterruptedException e) {
        // Swallow error
        Thread.currentThread().interrupt();
        break;
      }
    }
    return false;
  }

  @Override
  protected InputStream getInputStreamImpl() {
    return in;
  }

  @Override
  protected long getLengthImpl() {
    return firstLength;
  }

  public long getActualRange() {
    return this.actualRange;
  }

  public boolean isRedirected() {
    return redirected;
  }

  public String getRedirectUrl() {
    return redirectUrl;
  }

  @Override
  protected void closeImpl() {
    logger.info("Closing chunk retriever");
    if (this.in != null) {
      try {
        in.close();
      } catch (IOException e) {
        // Swallow error
      }
      in = null;
    }
    if (this.response != null) {
      response.close();
      response = null;
    }
    if (stop.get() || this.chunk == null) {
      this.httpClient = null;
    }
  }

  public String getHeader(String name) {
    if (this.response != null) {
      return this.response.getHeader(name);
    }
    return null;
  }
}
