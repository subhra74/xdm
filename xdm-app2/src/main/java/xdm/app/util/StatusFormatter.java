package xdm.app.util;

import xdm.DownloadEntry;
import xdm.DownloadQueue;
import xdm.QueueManager;
import xdm.core.XDMConstants;
import xdm.ui.res.StringResource;

import static xdm.core.util.FormatUtilities.formatSize;

public class StatusFormatter {
  public static String getFormattedStatus(DownloadEntry ent) {
    String statStr = "";
    if (ent.getQueueId() != null) {
      DownloadQueue q = QueueManager.getInstance().getQueueById(ent.getQueueId());
      String qname = "";
      if (q != null && q.getQueueId() != null) {
        qname = q.getQueueId().length() > 0 ? "[ " + q.getName() + " ] " : "";
      }
      statStr += qname;
    }

    if (ent.getState() == XDMConstants.FINISHED) {
      statStr += StringResource.get("STAT_FINISHED");
    } else if (ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED) {
      statStr += StringResource.get("STAT_PAUSED");
    } else if (ent.getState() == XDMConstants.ASSEMBLING) {
      statStr += StringResource.get("STAT_ASSEMBLING");
    } else {
      statStr += StringResource.get("STAT_DOWNLOADING");
    }
    String sizeStr = formatSize(ent.getSize());
    if (ent.getState() == XDMConstants.FINISHED) {
      return statStr + " " + sizeStr;
    } else {
      if (ent.getSize() > 0) {
        String downloadedStr = formatSize(ent.getDownloaded());
        String progressStr = ent.getProgress() + "%";
        return statStr + " " + progressStr + " [ " + downloadedStr + " / " + sizeStr + " ]";
      } else {
        return statStr
            + (ent.getProgress() > 0 ? (" " + ent.getProgress() + "%") : "")
            + (ent.getDownloaded() > 0
                ? " " + formatSize(ent.getDownloaded())
                : (ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED
                    ? ""
                    : " ..."));
      }
    }
  }
}
