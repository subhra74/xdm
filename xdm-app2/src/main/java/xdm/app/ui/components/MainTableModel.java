package xdm.app.ui.components;

import xdm.app.constants.ViewMode;
import xdm.app.services.core.DownloadStoreService;

import javax.swing.table.AbstractTableModel;

public class MainTableModel extends AbstractTableModel {
  private ViewMode viewMode = ViewMode.FINISHED;
  private final String[] inProgressHeaders = new String[] {"Name", "Size", "Date", "%", "Status"};
  private final String[] finishedHeaders = new String[] {"Name", "Size", "Date"};

  public void setViewMode(ViewMode viewMode) {
    this.viewMode = viewMode;
    fireTableStructureChanged();
  }

  public void downloadAdded(int index) {
    fireTableRowsInserted(index, index);
  }

  public void downloadUpdated(int index) {
    fireTableRowsUpdated(index, index);
  }

  @Override
  public int getRowCount() {
    if (viewMode == ViewMode.FINISHED) {
      return DownloadStoreService.getInstance().getFinishedDownloadsCount();
    }
    return DownloadStoreService.getInstance().getInProgressDownloadsCount();
  }

  @Override
  public int getColumnCount() {
    return viewMode == ViewMode.FINISHED ? finishedHeaders.length : inProgressHeaders.length;
  }

  @Override
  public String getColumnName(int column) {
    return viewMode == ViewMode.FINISHED ? inProgressHeaders[column] : finishedHeaders[column];
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    if (viewMode == ViewMode.FINISHED) {
      return DownloadStoreService.getInstance().getFinishedDownloadByIndex(rowIndex);
    } else {
      return DownloadStoreService.getInstance().getInProgressDownloadByIndex(rowIndex);
    }
  }
}
