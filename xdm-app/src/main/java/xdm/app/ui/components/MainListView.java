package xdm.app.ui.components;

import xdm.app.ui.screens.AppWindow;
import xdman.util.XDMUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class MainListView {
  private final MainListViewModel model;
  private final JTable table;
  private final JScrollPane jsp;
  private int editingRow = -1;

  public MainListView() {
    this.model = new MainListViewModel();
    this.table = new JTable(model);
    table.addMouseMotionListener(
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            var row = table.rowAtPoint(e.getPoint());
            if (row != -1) {
              if (editingRow != row) {
                table.editCellAt(row, 0);
                editingRow = row;
              }
            } else {
              table.editingCanceled(new ChangeEvent(table));
            }
          }
        });
    var renderer = new MainListViewRow(table, null);
    var editor = new MainListViewRow(table, model);

    table.setTableHeader(null);
    table.setRowHeight(renderer.getHeight());
    table.setDefaultRenderer(Object.class, renderer);
    table.setDefaultEditor(Object.class, editor);
    table.setFillsViewportHeight(true);
    table.setBackground(UIManager.getColor("Panel.background"));

    var sorter = new TableRowSorter<>(model);
    sorter.setComparator(0, new DownloadSorter());
    table.setRowSorter(sorter);

    List<RowSorter.SortKey> sortKeys = new ArrayList<>();
    sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING));
    sorter.setSortKeys(sortKeys);
    sorter.sort();

    jsp = new JScrollPane(table);
    jsp.setViewportBorder(new EmptyBorder(5, 0, 0, 0));
    jsp.setBorder(new EmptyBorder(0, 0, 0, 0));
    jsp.setAutoscrolls(true);
  }

  public void rowUpdated(int index) {
    model.fireTableRowsUpdated(index, index);
  }

  public void rowAdded(int index) {
    model.fireTableRowsInserted(index, index);
  }

  public Component getComponent() {
    return jsp;
  }
}
