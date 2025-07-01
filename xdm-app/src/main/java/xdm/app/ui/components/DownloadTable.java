package xdm.app.ui.components;

import xdm.app.constants.FilterItemType;
import xdm.app.models.DownloadEntry;
import xdm.app.models.FilterItem;
import xdm.app.ui.screens.AppWindow;
import xdman.util.XDMUtils;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class DownloadTable {
  private final DownloadTableModel model;
  private final JTable table;
  private final JScrollPane jsp;
  private final DownloadTableFilter tableFilter;
  private final int[] savedColumnWidths = new int[] {250, 70, 70, 150, 70, 70};
  private Consumer<List<DownloadEntry>> callback;

  public void setFilterItem(FilterItem filterItem) {
    saveColumnWidths();
    tableFilter.setFilterItem(filterItem);
    setInProgressMode(
        filterItem.getItemType() == FilterItemType.UNFINISHED
            || filterItem.getItemType() == FilterItemType.CATEGORY_UNFINISHED);
    restoreColumnWidth();
  }

  public void setFilterText(String filterText) {
    saveColumnWidths();
    tableFilter.setFilterText(filterText);
    model.fireTableDataChanged();
    restoreColumnWidth();
  }

  public Component getComponent() {
    return jsp;
  }

  public void focus() {
    table.requestFocusInWindow();
  }

  public void setInProgressMode(boolean inProgressMode) {
    this.model.setInProgressMode(inProgressMode);
  }

  public DownloadTable() {
    var renderer = new DownloadTableRenderer();
    model = new DownloadTableModel();
    table = new JTable();
    table.setModel(model);

    var rowHeight = renderer.getCellHeight();
    table.setRowHeight(rowHeight);
    table.setDefaultRenderer(Object.class, renderer);
    table.setFillsViewportHeight(true);
    // table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.putClientProperty("TableHeader.cellMargins", new Insets(0, 10, 0, 0));
    var headerRenderer = (DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer();
    headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
    jsp = new JScrollPane(table);
    jsp.setViewportBorder(new MatteBorder(5, 0, 5, 0, UIManager.getColor("Table.background")));
    jsp.setAutoscrolls(true);
    jsp.setBorder(new LineBorder(UIManager.getColor("Component.borderColor"), 1));

    restoreColumnWidth();

    tableFilter = new DownloadTableFilter();

    var sorter = new TableRowSorter<>(model);
//    sorter.setComparator(0, new DownloadSorter(0));
//    sorter.setComparator(1, new DownloadSorter(1));
//    sorter.setComparator(2, new DownloadSorter(2));
//    sorter.setComparator(3, new DownloadSorter(3));
    sorter.setRowFilter(tableFilter);
    table.setRowSorter(sorter);

    List<RowSorter.SortKey> sortKeys = new ArrayList<>();
    sortKeys.add(new RowSorter.SortKey(2, SortOrder.DESCENDING));
    sorter.setSortKeys(sortKeys);
    sorter.sort();

    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (e.getValueIsAdjusting()) {
                return;
              }
              if (callback != null) {
                callback.accept(getSelectedItems());
              }
            });
    model.listChanged();
  }

  public void rowUpdated(int index) {
    model.fireTableRowsUpdated(index, index);
  }

  public void rowAdded(int index) {
    model.fireTableRowsInserted(index, index);
  }

  public void setSelectionCallback(Consumer<List<DownloadEntry>> callback) {
    this.callback = callback;
  }

  public List<DownloadEntry> getSelectedItems() {
    var indexes = table.getSelectedRows();
    var list = new ArrayList<DownloadEntry>(indexes.length);
    for (int index : indexes) {
      var entry = model.getItemAt(table.convertRowIndexToModel(index));
      if (entry != null) {
        list.add(entry);
      }
    }
    return list;
  }

  private void saveColumnWidths() {
    final var columnModel = table.getColumnModel();
    for (var column = 0; column < table.getColumnCount(); column++) {
      var col = columnModel.getColumn(column);
      savedColumnWidths[column] = col.getPreferredWidth();
    }
  }

  public final void restoreColumnWidth() {
    final var columnModel = table.getColumnModel();
    for (var column = 0; column < table.getColumnCount(); column++) {
      var col = columnModel.getColumn(column);
      var width = savedColumnWidths[column];
      col.setPreferredWidth(width);
    }
  }

  public void installPopupMenu(JPopupMenu popupMenu, AppWindow window) {
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              AppMenuHandler.openFile(window);
            }
          }

          @Override
          public void mouseReleased(MouseEvent me) {
            if (me.getButton() == MouseEvent.BUTTON3
                || SwingUtilities.isRightMouseButton(me)
                || me.isPopupTrigger()
                || XDMUtils.isMacPopupTrigger(me)) {
              Point p = me.getPoint();
              if (table.getRowCount() < 1) return;
              if (table.getSelectedRow() < 0) {
                int row = table.rowAtPoint(p);
                if (row >= 0) {
                  table.setRowSelectionInterval(row, row);
                }
              }
              if (table.getSelectedRows().length > 0) {
                popupMenu.show(table, me.getX(), me.getY());
              }
            }
          }
        });
  }
}
