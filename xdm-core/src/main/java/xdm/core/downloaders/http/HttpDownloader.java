package xdm.core.downloaders.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.XDMConstants;
import xdm.core.downloaders.AbstractChunkRetriever;
import xdm.core.downloaders.AbstractSegmentedDownloader;
import xdm.core.downloaders.Chunk;
import xdm.core.downloaders.DownloaderType;
import xdm.core.network.http.PoolingHttpClient;
import xdm.core.network.http.impl.PoolingHttpClientImpl;
import xdm.core.util.*;

public class HttpDownloader extends AbstractSegmentedDownloader {
  private final HttpMetadata metadata;
  private final PoolingHttpClient httpClient;
  private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);

  public HttpDownloader(
      long id,
      String tempFolder,
      HttpMetadata metadata,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    super(DownloaderType.Http, id, tempFolder, listener, credentialProvider);
    this.metadata = metadata;
    this.httpClient = new PoolingHttpClientImpl(100);
  }

  @Override
  public synchronized AbstractChunkRetriever createChannel(Chunk chunk) {
    return new HttpChunkRetriever(
        chunk, metadata.getUrl(), metadata.getHeaders(), length.get(), this.httpClient);
  }

  @Override
  public int getType() {
    return XDMConstants.HTTP;
  }

  @Override
  protected void chunkConfirmed(Chunk c) {
    try {
      // logic
      // if the response has html content type and no attachment
      // no matter what is the target file extension, if any, will be changed to html.
      // If the download
      // has video conversion option, then conversion format will be removed.
      // in case of having an attachment, attachment extension will be used
      String fileName = getOutputFileName();
      HttpChunkRetriever hc = (HttpChunkRetriever) c.getChunkRetriever();
      super.getLastModifiedDate(c);
      if (hc.isRedirected()) {
        metadata.setUrl(hc.getRedirectUrl());
      }

      String contentDispositionHeader = hc.getHeader("content-disposition");
      String contentType = hc.getHeader("content-type");

      if (contentDispositionHeader == null
          && StringUtils.containsIgnoreCase(contentType, "text/html")) {
        metadata.setFileName(XDMUtils.getFileNameWithoutExtension(fileName) + ".html");
        outputFormat = 0;
      }

      boolean nameSet = false;
      if (contentDispositionHeader != null && outputFormat == 0) {
        String name = NetUtils.getNameFromContentDisposition(contentDispositionHeader);
        if (name != null) {
          metadata.setFileName(name);
          nameSet = true;
        }
      }
      if (!nameSet) {
        String ext = XDMUtils.getExtension(fileName);
        if (StringUtils.isNullOrEmptyOrBlank(ext)) {
          String newExt = MimeUtil.getFileExt(contentType);
          if (newExt != null) {
            metadata.setFileName(fileName + "." + newExt);
          }
        }
      }
    } finally {
      MetadataIO.save(metadata);
    }
  }

  @Override
  public HttpMetadata getMetadata() {
    return this.metadata;
  }
}
