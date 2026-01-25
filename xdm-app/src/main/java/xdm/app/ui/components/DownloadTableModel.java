package xdm.app.ui.components;

import javax.swing.table.*;

import xdm.app.AppContext;
import xdm.app.data.DbRecord;
import xdman.ListChangeListener;
import xdman.util.Logger;

public class DownloadTableModel extends AbstractTableModel implements ListChangeListener {

  private final String[] headers = new String[] {"Name", "Size", "Date", "Status", "ETA", "Speed"};
  private boolean inProgress;

  public DownloadTableModel() {}

  public void setInProgressMode(boolean inProgress) {
    this.inProgress = inProgress;
    fireTableStructureChanged();
  }

  @Override
  public int getColumnCount() {
    return inProgress ? headers.length : 4;
  }

  @Override
  public int getRowCount() {
    return AppContext.INSTANCE.getDb().getSize();
  }

  @Override
  public String getColumnName(int column) {
    return headers[column];
  }

  @Override
  public Class<?> getColumnClass(int c) {
    return DbRecord.class;
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
    Integer index = AppContext.INSTANCE.getDb().indexById(id);
    if (index != null) {
      fireTableRowsUpdated(index, index);
    }
  }

  //  private void refreshIdMap() {
  //    idIndexMap = new HashMap<>();
  //    for (int i = 0; i < idList.size(); i++) {
  //      idIndexMap.put(idList.get(i), i);
  //    }
  //  }

  public DbRecord getItemAt(int index) {
    return AppContext.INSTANCE.getDb().getByIndex(index);
  }
}
