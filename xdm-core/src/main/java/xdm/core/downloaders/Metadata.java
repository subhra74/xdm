package xdm.core.downloaders;

import lombok.*;
import xdm.core.network.http.HeaderCollection;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static xdm.core.util.SerializationUtils.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
public abstract class Metadata {
  protected long id;
  protected String cookies;
  protected HeaderCollection headers;
  protected String fileName;
  protected String contentType;
  protected boolean
      autoSelectFolder; // Download folder is set manually as opposed to category based one
  protected String folder;
  private long dateAdded;
  private String originPage;
  private boolean
      keepFileName; // Change file extension in case of redirection but keep file name same
  protected long fileSize;

  public abstract String getPrimaryUrl();

  public synchronized void save(DataOutputStream outputStream) throws IOException {
    writeNullable(id, outputStream);
    writeNullable(fileName, outputStream);
    writeNullable(contentType, outputStream);
    writeNullable(cookies, outputStream);
    writeNullable(autoSelectFolder, outputStream);
    writeNullable(folder, outputStream);
    writeNullable(dateAdded, outputStream);
    writeNullable(originPage, outputStream);
    writeNullable(keepFileName, outputStream);
    writeNullable(fileSize, outputStream);
    outputStream.writeBoolean(headers != null);
    if (headers != null) {
      headers.writeTo(outputStream);
    }
  }

  public synchronized void read(DataInputStream inputStream) throws IOException {
    this.id = readLong(inputStream, -1);
    this.fileName = readStr(inputStream);
    this.contentType = readStr(inputStream);
    this.cookies = readStr(inputStream);
    this.autoSelectFolder = readBoolean(inputStream, false);
    this.folder = readStr(inputStream);
    this.dateAdded = readLong(inputStream, -1);
    this.originPage = readStr(inputStream);
    this.keepFileName = readBoolean(inputStream, false);
    this.fileSize = readLong(inputStream, -1);
    if (inputStream.readBoolean()) {
      this.headers = new HeaderCollection();
      this.headers.readFrom(inputStream);
    }
  }
}
