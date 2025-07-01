package xdm.app.models;

import lombok.Data;
import lombok.EqualsAndHashCode;
import xdm.app.constants.DownloadStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

@EqualsAndHashCode(callSuper = true)
@Data
public class InprogressDownloadEntry extends DownloadEntry {
  private int progress;
  private DownloadStatus status;
  private float downloadSpeed;
  private long eta;

  @Override
  public void writeTo(DataOutputStream ds) throws IOException {
    super.writeTo(ds);
    ds.writeInt(this.progress);
    ds.writeUTF(String.valueOf(this.status));
    ds.writeFloat(this.downloadSpeed);
    ds.writeLong(this.eta);
  }

  @Override
  public void readFrom(DataInputStream ds) throws IOException {
    super.readFrom(ds);
    var progress = ds.readInt();
    var status = DownloadStatus.valueOf(ds.readUTF());
    var downloadSpeed = ds.readFloat();
    var eta = ds.readLong();
    this.progress = progress;
    this.status = status;
    this.downloadSpeed = downloadSpeed;
    this.eta = eta;
  }
}
