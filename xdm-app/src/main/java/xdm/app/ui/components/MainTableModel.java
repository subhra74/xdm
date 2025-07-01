package xdm.app.ui.components;

import javax.swing.table.*;
import xdm.app.AppContext;
import xdm.app.models.DownloadEntry;
import xdman.ListChangeListener;
import xdman.util.Logger;

public class MainTableModel extends AbstractTableModel implements ListChangeListener {

  @Override
  public int getColumnCount() {
    return 1;
  }

  @Override
  public int getRowCount() {
    return AppContext.INSTANCE.getDownloadsDbService().size();
  }

  @Override
  public Class<?> getColumnClass(int c) {
    return DownloadEntry.class;
  }

  @Override
  public Object getValueAt(int row, int col) {
    return getItemAt(row);
  }

  @Override
  public void listChanged() {
    Logger.log("List changed");
    fireTableDataChanged();
  }

  @Override
  public void listItemUpdated(long id) {
    Logger.log("List updated");
    Integer index = AppContext.INSTANCE.getDownloadsDbService().indexById(id);
    if (index != null) {
      fireTableRowsUpdated(index, index);
    }
  }

  public DownloadEntry getItemAt(int index) {
    return AppContext.INSTANCE.getDownloadsDbService().getByIndex(index);
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return true;
  }
}
