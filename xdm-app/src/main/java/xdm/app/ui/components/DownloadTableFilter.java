package xdm.app.ui.components;

import lombok.Setter;
import xdm.app.constants.DownloadEntryState;
// import xdm.app.models.DownloadEntry;
import xdm.app.data.DbRecord;
import xdm.app.data.RecordStatus;
import xdm.app.models.FilterItem;
import xdm.app.utils.StringUtils;
import xdman.XDMConstants;

import javax.swing.*;
import java.util.Locale;

public class DownloadTableFilter extends RowFilter<DownloadTableModel, Integer> {
  @Setter private String filterText;
  @Setter private FilterItem filterItem;

  @Override
  public boolean include(Entry<? extends DownloadTableModel, ? extends Integer> entry) {
    var index = entry.getIdentifier();
    var model = (DownloadTableModel) entry.getModel();
    if (index < 0 || index > model.getRowCount()) {
      return false;
    }
    if (filterItem == null) {
      return true;
    }
    var ent = (DbRecord) model.getValueAt(index, 0);
    boolean matched;
    switch (filterItem.getItemType()) {
      case ALL:
        matched = true;
        break;
      case FINISHED:
        matched = ent.getStatus() == RecordStatus.FINISHED;
        break;
      case UNFINISHED:
        matched = ent.getStatus() != RecordStatus.FINISHED;
        break;
      case CATEGORY_FINISHED:
        matched = ent.getStatus() == RecordStatus.FINISHED && matchCategory(filterItem, ent);
        break;
      case CATEGORY_UNFINISHED:
        matched = ent.getStatus() != RecordStatus.FINISHED && matchCategory(filterItem, ent);
        break;
      default:
        matched = false;
        break;
    }

    if (!matched) {
      return false;
    }
    var name = ent.getFileName();
    if (!StringUtils.isNullOrEmpty(filterText)) {
      return name.toLowerCase(Locale.ENGLISH).contains(filterText);
    }
    return true;
  }

  private boolean matchCategory(FilterItem item, DbRecord entry) {
    // dummy impl
    return true;
  }
}
