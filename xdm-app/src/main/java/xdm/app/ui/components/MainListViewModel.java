package xdm.app.ui.components;

import javax.swing.table.*;
import xdm.app.AppContext;
import xdm.app.data.DbRecord;
import xdman.ListChangeListener;
import xdman.util.Logger;

public class MainListViewModel extends AbstractTableModel implements ListChangeListener {

  @Override
  public int getColumnCount() {
    return 1;
  }

  @Override
  public int getRowCount() {
    return AppContext.INSTANCE.getDb().getSize();
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

  public DbRecord getItemAt(int index) {
    return AppContext.INSTANCE.getDb().getByIndex(index);
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return true;
  }
}
