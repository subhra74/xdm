package xdm.core.downloaders.hls;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.core.DownloadProgressListener;
import xdm.core.InteractiveCredentialProvider;
import xdm.core.downloaders.*;
import xdm.core.media.parser.hls.HlsMediaPlaylist;
import xdm.core.media.parser.hls.HlsMediaSegment;
import xdm.core.media.parser.hls.HlsParser;
import xdm.core.network.http.HttpResponse;

public class HlsDownloader extends AbstractMultiSourceDownloader {
  private static final Logger logger = LoggerFactory.getLogger(HlsDownloader.class);
  private final HlsMetadata metadata;
  private final Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
  private final AtomicInteger pieceCompletedCount = new AtomicInteger(0);

  public HlsDownloader(
      long id,
      String folder,
      HlsMetadata hlsRequestDetails,
      DownloadProgressListener listener,
      InteractiveCredentialProvider credentialProvider) {
    super(DownloaderType.Hls, id, folder, listener, credentialProvider);
    this.metadata = hlsRequestDetails;
  }

  @Override
  protected void initDownload() throws IOException {
    if (this.metadata.getUrl() == null) {
      throw new IOException("Video url missing");
    }
    this.hasSeparateStreams = this.metadata.hasSeparateAudio();
    CountDownLatch latch = new CountDownLatch(this.metadata.hasSeparateAudio() ? 2 : 1);
    AtomicReference<List<String>> videoManifestFile = new AtomicReference<>();
    AtomicReference<List<String>> audioManifestFile = new AtomicReference<>();
    AtomicBoolean error = new AtomicBoolean(false);
    executorService.submit(
        () -> {
          try {
            byte[] bytes = downloadManifest(this.metadata.getUrl().toString());
            if (bytes == null) {
              error.set(true);
              return;
            }
            List<String> lines = Arrays.asList(new String(bytes, "utf-8").split("\n"));
            videoManifestFile.set(lines);
          } catch (Exception ex) {
            logger.error("Error downloading manifest", ex);
            error.set(true);
          } finally {
            latch.countDown();
          }
        });
    if (this.metadata.hasSeparateAudio()) {
      executorService.submit(
          () -> {
            try {
              byte[] bytes = downloadManifest(this.metadata.getAudioUrl().toString());
              if (bytes == null) {
                error.set(true);
                return;
              }
              List<String> lines = Arrays.asList(new String(bytes, "utf-8").split("\n"));
              audioManifestFile.set(lines);
            } catch (Exception ex) {
              logger.error("Error downloading manifest", ex);
              error.set(true);
            } finally {
              latch.countDown();
            }
          });
    }

    try {
      latch.await();
      if (error.get()) {
        logger.error("Manifest download error");
        throw new IOException("Failed to download manifest");
      }
      int totalCount = 0;
      HlsMediaPlaylist videoPlayList =
          HlsParser.parseMediaSegments(videoManifestFile.get(), this.metadata.getUrl().toString());
      if (videoPlayList == null) {
        throw new IOException("Error getting metadata");
      }
      if (stopFlag.get()) {
        return;
      }
      totalCount += videoPlayList.getMediaSegments().size();
      HlsMediaPlaylist audioPlayList = null;
      if (this.metadata.hasSeparateAudio()) {
        audioPlayList =
            HlsParser.parseMediaSegments(
                audioManifestFile.get(), this.metadata.getAudioUrl().toString());
        if (audioPlayList != null) {
          totalCount += audioPlayList.getMediaSegments().size();
        }
      }
      retrieveKeys(videoPlayList, audioPlayList, keyCache);
      int count = 0;
      int ac = 0;
      int vc = 0;
      this.pieces = new ArrayList<>(totalCount);
      for (HlsMediaSegment ms : videoPlayList.getMediaSegments()) {
        DownloadablePiece dp =
            DownloadablePiece.builder()
                .url(ms.getUrl().toString())
                .iv(ms.getIv())
                .keyUrl(Optional.ofNullable(ms.getKeyUrl()).map(URI::toString).orElse(null))
                .byteRange(ms.getByteRange())
                .id("VIDEO_" + vc + "_" + UUID.randomUUID().toString())
                .sequence(count)
                .tag("VIDEO")
                .status(PieceStatus.Ready)
                .build();
        this.pieces.add(dp);
        count++;
        vc++;
      }
      if (audioPlayList != null) {
        for (HlsMediaSegment ms : audioPlayList.getMediaSegments()) {
          DownloadablePiece dp =
              DownloadablePiece.builder()
                  .url(ms.getUrl().toString())
                  .iv(ms.getIv())
                  .keyUrl(Optional.ofNullable(ms.getKeyUrl()).map(URI::toString).orElse(null))
                  .byteRange(ms.getByteRange())
                  .id("AUDIO_" + ac + "_" + UUID.randomUUID().toString())
                  .sequence(count)
                  .tag("AUDIO")
                  .status(PieceStatus.Ready)
                  .build();
          this.pieces.add(dp);
          count++;
          ac++;
        }
      }

    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      logger.error("Thread interrupted", ex);
      if (!stopFlag.get()) {
        throw new IOException("Error getting metadata");
      }
    } catch (Exception ex) {
      logger.error("Error in init", ex);
      if (!stopFlag.get()) {
        throw new IOException("Error getting metadata");
      }
    }
  }

  @Override
  protected void downloadChunks() throws IOException {
    int count = (int) this.pieces.stream().filter(pc -> pc.getStatus() != PieceStatus.Done).count();
    final CountDownLatch latch = new CountDownLatch(count);
    this.pieceGrabbers = new ArrayList<>(count);
    for (DownloadablePiece pc : this.pieces) {
      if (pc.getStatus() != PieceStatus.Done) {
        PieceGrabber pg =
            new PieceGrabber(
                pc,
                this.httpClient,
                this.stopFlag,
                this.metadata,
                this.folder,
                this::onDownloadProgress,
                p -> {
                  latch.countDown();
                  if (p.getStatus() == PieceStatus.Done) {
                    this.pieceCompletedCount.incrementAndGet();
                    this.progress.set(this.pieceCompletedCount.get() * 100 / this.pieces.size());
                    this.onDownloadProgress(0);
                  }
                });
        this.pieceGrabbers.add(pg);
        this.executorService.submit(pg);
      }
    }
    try {
      latch.await();
    } catch (InterruptedException ex) {
      logger.error("Thread interrupted", ex);
      Thread.currentThread().interrupt();
    }
  }

  private void retrieveKeys(
      HlsMediaPlaylist videoPlayList, HlsMediaPlaylist audioPlayList, Map<String, byte[]> keyCache)
      throws IOException {
    AtomicBoolean error = new AtomicBoolean(false);
    Set<String> keyUrls = new HashSet<>();
    if (videoPlayList.isEncrypted()) {
      keyUrls.addAll(
          videoPlayList.getMediaSegments().stream()
              .map(s -> s.getUrl().toString())
              .collect(Collectors.toList()));
    }
    if (audioPlayList != null && audioPlayList.isEncrypted()) {
      keyUrls.addAll(
          audioPlayList.getMediaSegments().stream()
              .map(s -> s.getUrl().toString())
              .collect(Collectors.toList()));
    }
    if (stopFlag.get()) {
      return;
    }
    CountDownLatch counter = new CountDownLatch(keyUrls.size());
    for (String keyUrl : keyUrls) {
      executorService.submit(
          () -> {
            try {
              if (error.get() || stopFlag.get()) {
                return;
              }
              byte[] data = downloadManifest(keyUrl);
              if (data == null) {
                error.set(true);
                return;
              }
              keyCache.put(keyUrl, data);
            } finally {
              counter.countDown();
            }
          });
    }
    if (error.get()) {
      throw new IOException("Unable to get keys");
    }
  }

  private byte[] downloadManifest(String url) {
    try {
      while (!stopFlag.get()) {
        try (HttpResponse response =
            this.httpClient.get(
                url, this.metadata.getHeaders(), this.metadata.getCookies(), null)) {
          if (stopFlag.get()) return null;
          int code = response.getStatusCode();
          if (code != 200 && code != 206) {
            logger.error("Manifest download failed");
            return null;
          }
          ByteArrayOutputStream bout = new ByteArrayOutputStream();
          byte[] b = new byte[8192];
          try (InputStream inputStream = response.getInputStream()) {
            while (!stopFlag.get()) {
              int x = inputStream.read(b);
              if (x == -1) break;
              bout.write(b, 0, x);
            }
            return bout.toByteArray();
          }
        } catch (IOException ex) {
          logger.error("Error downloading manifest", ex);
          Thread.sleep(3000);
        }
      }
    } catch (InterruptedException ex) {
      logger.error("Thread interrupted", ex);
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      logger.error("Error downloading manifest", ex);
    }
    return null;
  }

  @Override
  protected boolean restoreState() {
    File stateFile = new File(this.folder, "state.dat");
    if (!stateFile.exists()) {
      stateFile = new File(this.folder, "state.dat.tmp");
      if (!stateFile.exists()) {
        return false;
      }
    }
    try (DataInputStream ds =
        new DataInputStream(new BufferedInputStream(new FileInputStream(stateFile)))) {
      long downloaded = ds.readLong();
      int progress = ds.readInt();
      int size = ds.readInt();
      List<DownloadablePiece> pieces = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        DownloadablePiece piece = DownloadablePiece.read(ds);
        pieces.add(piece);
      }
      boolean hasSeparateStreams = ds.readBoolean();
      boolean assembling = ds.readBoolean();
      int keySize = ds.readInt();
      HashMap<String, byte[]> keys = new HashMap<>();
      for (int i = 0; i < keySize; i++) {
        String key = ds.readUTF();
        int keyDataSize = ds.readInt();
        byte[] data = new byte[keyDataSize];
        if (ds.read(data) != data.length) {
          throw new IOException("Unexpected EOF");
        }
        keys.put(key, data);
      }
      this.downloaded.set(downloaded);
      this.progress.set(progress);
      this.downloadProgressData.setLastProgress(progress);
      this.downloadProgressData.setTotalDownloadedBytes(downloaded);
      this.pieces = pieces;
      this.hasSeparateStreams = hasSeparateStreams;
      this.assembling = assembling;
      this.keyCache.putAll(keys);
      return true;
    } catch (Exception ex) {
      logger.error("Error restoring state", ex);
      return false;
    }
  }

  @Override
  protected void saveState() {
    if (!this.init.get() || !this.finished.get()) {
      return;
    }
    File tmpStateFile = new File(this.folder, "state.dat.tmp");
    File stateFile = new File(this.folder, "state.dat");
    try (OutputStream fileOut = new FileOutputStream(tmpStateFile);
        OutputStream bufferedOut = new BufferedOutputStream(fileOut, 8192);
        DataOutputStream dataOut = new DataOutputStream(bufferedOut)) {
      dataOut.writeLong(this.downloaded.get());
      dataOut.writeInt(this.downloadProgressData.getProgress());
      dataOut.writeInt(this.pieces.size());
      for (DownloadablePiece p : this.pieces) {
        DownloadablePiece.write(p, dataOut);
      }
      dataOut.writeBoolean(this.hasSeparateStreams);
      dataOut.writeBoolean(this.assembling);
      dataOut.writeInt(keyCache.size());
      for (Map.Entry<String, byte[]> entry : keyCache.entrySet()) {
        dataOut.writeUTF(entry.getKey());
        byte[] b = entry.getValue();
        dataOut.writeInt(b.length);
        dataOut.write(b);
      }
    } catch (Exception ex) {
      logger.error("Error in save state", ex);
    }
    if (stateFile.exists()) {
      stateFile.delete();
    }
    tmpStateFile.renameTo(stateFile);
  }

  @Override
  public Metadata getMetadata() {
    return this.metadata;
  }
}
