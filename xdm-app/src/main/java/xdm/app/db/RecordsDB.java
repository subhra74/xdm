package xdm.app.db;

import xdm.app.constants.DownloadEntryState;
import xdm.app.models.DownloadEntry;
import xdman.util.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RecordsDB {
  private static final Logger logger = Logger.getLogger(RecordsDB.class);
  private static final int defaultCapacity = 1000;
  private static final int recordSize = 512;
  private static final int headerSize = 8;
  private int capacity = 0;
  private int recordCount = 0;
  private ByteBuffer buffer;
  private final String fileName;
  private final String directory;
  private Map<Long, Integer> indexMap;
  private FileChannel channel;
  private RandomAccessFile file;
  private static final byte[] arr = new byte[512];

  public RecordsDB(String fileName, String directory) {
    this.fileName = fileName;
    this.directory = directory;
  }

  public void append(DownloadEntry entry) throws IOException {
    ensureCapacity();
    var offset = recordCount * recordSize + headerSize;
    buffer.position(offset);
    writeToBuffer(buffer, entry);
    indexMap.put(entry.getId(), recordCount);
    recordCount++;
    buffer.position(4);
    buffer.putInt(recordCount);
  }

  public void delete(List<Integer> indices) throws IOException {
    var set = new HashSet<>(indices);
    // Inefficient but simple implementation
    var newFileName = this.file + ".bak";
    var newFile = new File(directory, newFileName);
    try (var file = new BufferedOutputStream(new FileOutputStream(newFile))) {
      var b = ByteBuffer.allocate(headerSize);
      b.putInt(this.capacity);
      b.putInt(this.recordCount - indices.size());
      file.write(b.array());
      var b1 = new byte[512];
      for (var i = 0; i < recordCount; i++) {
        if (set.contains(i)) {
          continue;
        }
        var pos = i * recordSize + headerSize;
        buffer.position(pos);
        buffer.get(b1);
        file.write(b1);
      }
    }
    discardChannelAndBuffer();
    var currentFile = new File(directory, fileName);
    currentFile.delete();
    newFile.renameTo(currentFile);
    file = new RandomAccessFile(new File(this.directory, this.fileName), "rw");
    allocateBuffer();
    buildIndex();
  }

  public int size() {
    return recordCount;
  }

  public DownloadEntry getByIndex(int index) {
    if (index < 0 || index >= recordCount) {
      throw new ArrayIndexOutOfBoundsException("Index out of bound");
    }
    var offset = index * recordSize + headerSize;
    buffer.position(offset);
    var entry = new DownloadEntry();
    readFromBuffer(buffer, entry);
    return entry;
  }

  public DownloadEntry getById(long id) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return null;
    }
    return getByIndex(index);
  }

  public void open() throws IOException {
    var f = new File(directory, fileName);
    var fileExists = f.exists();
    logger.info("DB exists: %s", fileExists);
    if (!fileExists) {
      capacity = defaultCapacity;
      recordCount = 0;
      expandFile();
      indexMap = new HashMap<>(capacity);
      return;
    }

    file = new RandomAccessFile(new File(this.directory, this.fileName), "rw");
    allocateBuffer();
    buildIndex();
  }

  private void ensureCapacity() throws IOException {
    if (recordCount == capacity) {
      logger.info("Extending buffer by 1000");
      capacity += 1000;
      expandFile();
    }
  }

  private void expandFile() throws IOException {
    discardChannelAndBuffer();
    file = new RandomAccessFile(new File(this.directory, this.fileName), "rw");
    file.seek(0);
    file.setLength(capacity * (long) recordSize + headerSize);
    allocateBuffer();
    buffer.putInt(capacity);
    buffer.putInt(recordCount);
  }

  private void allocateBuffer() throws IOException {
    channel = file.getChannel();
    buffer =
        channel.map(
            FileChannel.MapMode.READ_WRITE,
            0, // position
            channel.size());
  }

  private void discardChannelAndBuffer() throws IOException {
    if (channel != null) {
      channel.close();
      channel = null;
    }
    if (buffer != null) {
      buffer = null;
    }
    if (file != null) {
      file.close();
    }
  }

  private void buildIndex() {
    buffer.position(0);
    capacity = buffer.getInt();
    recordCount = buffer.getInt();
    indexMap = new HashMap<>(capacity);
    for (var i = 0; i < recordCount; i++) {
      var blockOffset = (i * recordSize) + headerSize;
      var blockId = buffer.getLong(blockOffset);
      indexMap.put(blockId, i);
    }
  }

  public void writeToBuffer(ByteBuffer buffer, DownloadEntry entry) {
    buffer.putLong(entry.getId()); // 0
    buffer.put((byte) entry.getState().ordinal()); // 8
    buffer.putLong(entry.getSize()); // 9
    buffer.putLong(entry.getDownloaded()); // 17
    buffer.put((byte) entry.getProgress()); // 25
    buffer.putLong(entry.getDateEpoch()); // 26
    var bytes =
        entry
            .getFileName()
            .substring(0, Math.min(entry.getFileName().length(), 230))
            .getBytes(StandardCharsets.UTF_8);
    // this should be <= 460 bytes when encoded in utf-8
    buffer.putShort((short) bytes.length); // 34
    buffer.put(bytes); // 36
  }

  public void readFromBuffer(ByteBuffer buffer, DownloadEntry entry) {
    entry.setId(buffer.getLong());
    entry.setState(DownloadEntryState.values()[buffer.get()]);
    entry.setSize(buffer.getLong());
    entry.setDownloaded(buffer.getLong());
    entry.setProgress(buffer.get());
    entry.setDateEpoch(buffer.getLong());
    var len = buffer.getShort();
    buffer.get(arr, 0, len);
    entry.setFileName(new String(arr, 0, len, StandardCharsets.UTF_8));
  }

  public void updateDownloadState(long id, DownloadEntryState state) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return;
    }
    var offset = index * recordSize + headerSize + 8;
    buffer.put(offset, (byte) state.ordinal());
  }

  public void updateDownloadSize(long id, long size) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return;
    }
    var offset = index * recordSize + headerSize + 9;
    buffer.putLong(offset, size);
  }

  public void updateDownloadBytes(long id, long downloaded) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return;
    }
    var offset = index * recordSize + headerSize + 17;
    buffer.putLong(offset, downloaded);
  }

  public void updateDownloadProgress(long id, int progress) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return;
    }
    var offset = index * recordSize + headerSize + 25;
    buffer.put(offset, (byte) progress);
  }

  public void updateDownloadFileName(long id, String fileName) {
    Integer index = indexMap.get(id);
    if (index == null) {
      return;
    }
    var bytes =
        fileName.substring(0, Math.min(fileName.length(), 230)).getBytes(StandardCharsets.UTF_8);
    // this should be <= 460 bytes when encoded in utf-8
    var offset = index * recordSize + headerSize + 8;
    buffer.putShort((short) bytes.length);
    buffer.put(bytes);
  }
}
