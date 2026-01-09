package xdm.core.downloaders.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.XDMConstants;
import xdm.core.downloaders.*;
import xdm.core.net.HttpUtilsKt;
import xdm.core.network.http.PoolingHttpClient;
import xdm.core.network.http.impl.HttpClientImpl;
import xdm.core.network.http.impl.HttpClientImpl2;
import xdm.core.util.*;

import java.util.ArrayList;

public class HttpDownloader extends AbstractSegmentedDownloader {
  private final HttpSource metadata;
  private final PoolingHttpClient httpClient;
  private static final Logger logger = LoggerFactory.getLogger(HttpDownloader.class);

  public HttpDownloader(
      long id,
      String tempFolder,
      HttpSource metadata,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    super(DownloaderType.Http, id, tempFolder, listener, credentialProvider);
    this.metadata = metadata;
    this.httpClient = new HttpClientImpl2(100); // new PoolingHttpClientImpl(100);
  }

  @Override
  public synchronized AbstractChunkRetriever createChannel(Chunk chunk) {
    return new HttpChunkRetriever(
        chunk,
        metadata.getUrl(),
        metadata.getHeaders(),
        metadata.getCookies(),
        length.get(),
        this.httpClient);
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

      HttpChunkRetriever hc = (HttpChunkRetriever) c.getChunkRetriever();
      super.getLastModifiedDate(c);
      if (hc.isRedirected()) {
        metadata.setUrl(hc.getRedirectUrl());
      }

      String url = metadata.getUrl();
      String contentDisposition = hc.getHeader("content-disposition");
      String contentType = hc.getHeader("content-type");

      metadata.setFileName(
          HttpUtilsKt.getFileName(
              metadata.getFileName(),
              metadata.isKeepFileName(),
              url,
              contentDisposition,
              contentType));
    } finally {
      MetadataStore.save(metadata);
    }
  }

  @Override
  public HttpSource getMetadata() {
    return this.metadata;
  }

  @Override
  protected void cleanupConnections() {
    xdm.core.util.Logger.info("XDM","Cleanup done..");
    this.httpClient.close();
  }

  @Override
  protected void updateMetadataFinal(String fileName, String folder, long totalBytes) {
    this.metadata.setFileName(fileName);
    this.metadata.setFolder(folder);
    this.metadata.setFileSize(totalBytes);
    MetadataStore.save(this.metadata);
  }

  @Override
  protected void saveState() {
    if (length.get() < 0) return;
    TransactedIO.write(
        folder,
        "state.txt",
        fs -> {
          fs.writeLong(this.length.get());
          fs.writeLong(this.downloaded.get());
          fs.writeInt(this.chunks.size());
          for (int i = 0; i < chunks.size(); i++) {
            Chunk seg = chunks.get(i);
            fs.writeLong(seg.getId());
            fs.writeLong(seg.getLength());
            fs.writeLong(seg.getStartOffset());
            fs.writeLong(seg.getDownloaded());
          }
          SerializationUtils.writeNullable(this.lastModified, fs);
        },
        e -> logger.error(e.getMessage(), e));
  }

  @Override
  protected boolean restoreState() {
    chunks = new ArrayList<>();
    return TransactedIO.read(
        folder,
        "state.txt",
        reader -> {
          this.length.set(reader.readLong());
          this.downloaded.set(reader.readLong());
          int chunkCount = reader.readInt();
          for (int i = 0; i < chunkCount; i++) {
            long cid = reader.readLong();
            long len = reader.readLong();
            long off = reader.readLong();
            long dwn = reader.readLong();
            Chunk seg = new ChunkImpl(folder, cid, off, len, dwn);

            logger.info(
                "id: {} length: {} offset: {} download: {}",
                seg.getId(),
                seg.getLength(),
                seg.getStartOffset(),
                seg.getDownloaded());

            chunks.add(seg);
          }
          this.lastModified = SerializationUtils.readStr(reader);
        },
        e -> logger.error(e.getMessage(), e));
  }
}
