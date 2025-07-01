package xdm.app.models;

import lombok.*;
import xdm.app.constants.DownloadEntryState;

@ToString
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadEntry {
  private long id;
  private DownloadEntryState state;
  private long size;
  private long downloaded;
  private int progress;
  private long dateEpoch;
  private String fileName;
  private long eta;
  private float speed;
  private boolean selected;
}
