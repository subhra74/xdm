package xdm.app.ui.components;

import lombok.Setter;
import xdm.app.constants.DownloadEntryState;
// import xdm.app.models.DownloadEntry;
import xdm.app.data.DbRecord;
import xdm.app.data.RecordStatus;
import xdman.ui.res.StringResource;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MainListView {
  private final MainListViewModel model;
  private final JTable table;
  private final JScrollPane jsp;
  private int editingRow = -1;
  private final JPopupMenu contextMenu;
  private JMenuItem mSaveAs;
  private JMenuItem mRefresh;
  private JMenuItem mProgress;
  private JMenuItem mCopyUrl;
  private JMenuItem mCopyFile;
  private JMenuItem mProperty;
  @Setter private Consumer<Boolean> selectModeCallback;

  public MainListView() {
    this.model = new MainListViewModel();
    this.table = new JTable(model);
    this.contextMenu = createContextMenu(this.table);
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
    var renderer = new MainListViewRow(table);
    var editor = new MainListViewRow(table, model);
    editor.setOnMenuClick(entry -> showMenu(entry, editor));

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

    table
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting() && selectModeCallback != null) {
                selectModeCallback.accept(table.getSelectedRowCount() > 0);
              }
            });

    editor.setOnOpenFileClick(
        e -> AppMenuHandler.openFile(e, SwingUtilities.windowForComponent(jsp)));
    editor.setOnOpenFolderClick(
        e -> AppMenuHandler.openFolder(e, SwingUtilities.windowForComponent(jsp)));
    editor.setOnPauseClick(AppMenuHandler::pauseDownload);
    editor.setOnResumeClick(AppMenuHandler::resumeDownload);
    editor.setOnDeleteClick(
        e -> AppMenuHandler.deleteDownload(e, SwingUtilities.windowForComponent(jsp)));
  }

  private void showMenu(DbRecord entry, MainListViewRow editor) {
    prepareMenu(this.contextMenu, entry);
    editor.showMenu(this.contextMenu);
  }

  public void rowUpdated(int index) {
//    Logger.log("Model size: " + model.getRowCount() + " index: " + index);
    model.fireTableRowsUpdated(index, index);
  }

  public void rowAdded(int index) {
    if (table.isEditing()) {
      table.getCellEditor().cancelCellEditing();
    }
    model.fireTableRowsInserted(index, index);
  }

  public Component getComponent() {
    return jsp;
  }

  private ActionListener createMenuListener() {
    return e -> {
      if (e.getSource() instanceof JComponent c) {
        var name = c.getName();
        if (name == null) {
          return;
        }
        var ent = (DbRecord) contextMenu.getClientProperty("menu.context");
        switch (name) {
          case "CTX_SAVE_AS":
            break;
          case "MENU_REFRESH_LINK":
            break;
          case "LBL_SHOW_PROGRESS":
            break;
          case "CTX_COPY_URL":
            break;
          case "CTX_COPY_FILE":
            break;
          case "MENU_PROPERTIES":
            break;
        }
      }
    };
  }

  private JPopupMenu createContextMenu(JTable table) {
    ActionListener a = createMenuListener();

    var ctx = new JPopupMenu();
    mSaveAs = addMenuItem("CTX_SAVE_AS", ctx, a);
    mRefresh = addMenuItem("MENU_REFRESH_LINK", ctx, a);
    mProgress = addMenuItem("LBL_SHOW_PROGRESS", ctx, a);
    mCopyUrl = addMenuItem("CTX_COPY_URL", ctx, a);
    mCopyFile = addMenuItem("CTX_COPY_FILE", ctx, a);
    mProperty = addMenuItem("MENU_PROPERTIES", ctx, a);
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseReleased(MouseEvent me) {
            if (me.getButton() == MouseEvent.BUTTON3
                || SwingUtilities.isRightMouseButton(me)
                || me.isPopupTrigger()
                || XDMUtils.isMacPopupTrigger(me)) {
              if (table.getRowCount() < 1) return;
              if (table.getSelectedRows().length > 0) {
                ctx.show(table, me.getX(), me.getY());
              }
            }
          }
        });
    return ctx;
  }

  private void prepareMenu(JPopupMenu contextMenu, DbRecord entry) {
    mSaveAs.setVisible(entry.getStatus() != RecordStatus.FINISHED);
    mRefresh.setVisible(entry.getStatus() == RecordStatus.PAUSED);
    mProgress.setVisible(entry.getStatus() == RecordStatus.DOWNLOADING);
    mCopyFile.setVisible(entry.getStatus() == RecordStatus.FINISHED);
    contextMenu.putClientProperty("menu.context", entry);
  }

  private JMenuItem addMenuItem(String id, JComponent menu, ActionListener a) {
    var mItem = new JMenuItem(StringResource.get(id));
    mItem.setName(id);
    mItem.addActionListener(a);
    menu.add(mItem);
    return mItem;
  }
}
