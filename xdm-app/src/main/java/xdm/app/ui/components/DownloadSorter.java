package xdm.app.ui.components;

import xdm.app.AppContext;
import xdm.app.models.DownloadEntry;

import java.util.Comparator;

public class DownloadSorter implements Comparator<DownloadEntry> {

  @Override
  public int compare(DownloadEntry o1, DownloadEntry o2) {
    var sortKey = AppContext.INSTANCE.getConfigService().getSortKey();
    var ascending = AppContext.INSTANCE.getConfigService().isSortAscending();
    int res = 0;
    switch (sortKey) {
      case NAME: // sort by name
        res = o1.getFileName().compareTo(o2.getFileName());
        break;
      case SIZE: // sort by size
        res = Long.compare(o1.getSize(), o2.getSize());
        break;
      case DATE: // sort by date
        res = Long.compare(o1.getDateEpoch(), o2.getDateEpoch());
        break;
      case TYPE: // sort by type
        res = o1.getState().compareTo(o2.getState());
        break;
      default:
        break;
    }
    return ascending ? -res : res;
  }
}
