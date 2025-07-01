package xdm.app.models;

import lombok.Data;
import xdm.app.constants.FileNameFetchMode;
import xdm.core.util.StringUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Data
public class DownloadEntry {
  private String id;
  private String name;
  private String outputDir;
  private String primaryUrl;
  private String refererUrl;
  private String downloadType;
  private int maxSpeedLimitInKiB;
  private long fileSize;
  private LocalDateTime dateAdded;
  private FileNameFetchMode fileNameFetchMode;
  private AuthenticationInfo authenticationInfo;

  public void writeTo(DataOutputStream ds) throws IOException {
    ds.writeUTF(this.id);
    ds.writeUTF(this.name);
    ds.writeUTF(this.outputDir);
    ds.writeUTF(this.primaryUrl);
    if (!StringUtils.isNullOrEmpty(this.refererUrl)) {
      ds.writeBoolean(true);
      ds.writeUTF(this.refererUrl);
    } else {
      ds.writeBoolean(false);
    }
    ds.writeUTF(this.downloadType);
    ds.writeInt(this.maxSpeedLimitInKiB);
    ds.writeLong(this.fileSize);
    ds.writeLong(this.dateAdded.toEpochSecond(ZoneOffset.UTC));
    ds.writeUTF(String.valueOf(this.fileNameFetchMode));
    if (authenticationInfo != null) {
      ds.writeBoolean(true);
      ds.writeUTF(this.authenticationInfo.getUserName());
      ds.writeUTF(Optional.ofNullable(this.authenticationInfo.getPassword()).orElse(""));
    } else {
      ds.writeBoolean(false);
    }
  }

  public void readFrom(DataInputStream di) throws IOException {
    var id = di.readUTF();
    var name = di.readUTF();
    var outputDir = di.readUTF();
    var primaryUrl = di.readUTF();
    String refererUrl = null;
    if (di.readBoolean()) {
      refererUrl = di.readUTF();
    }
    var downloadType = di.readUTF();
    var maxSpeedLimitInKiB = di.readInt();
    var fileSize = di.readLong();
    var dateAdded = LocalDateTime.ofEpochSecond(di.readLong(), 0, ZoneOffset.UTC);
    var fileNameFetchMode = FileNameFetchMode.valueOf(di.readUTF());
    AuthenticationInfo authenticationInfo = null;
    if (di.readBoolean()) {
      authenticationInfo = new AuthenticationInfo(di.readUTF(), di.readUTF());
    }
    this.id = id;
    this.name = name;
    this.outputDir = outputDir;
    this.primaryUrl = primaryUrl;
    this.refererUrl = refererUrl;
    this.downloadType = downloadType;
    this.maxSpeedLimitInKiB = maxSpeedLimitInKiB;
    this.fileSize = fileSize;
    this.dateAdded = dateAdded;
    this.fileNameFetchMode = fileNameFetchMode;
    this.authenticationInfo = authenticationInfo;
  }
}
