package xdm.app.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import lombok.Setter;
import xdm.app.constants.DownloadEntryState;
import xdm.app.models.DownloadEntry;
import xdm.app.utils.AppUtils;
import xdm.core.util.FormatUtilities;
import xdman.ui.res.StringResource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EventObject;
import java.util.function.Consumer;

public class MainListViewRow implements TableCellRenderer, TableCellEditor {
  private JPanel panel;
  private JLabel lblInfo;
  private JLabel lblTitle;
  private JLabel icon;
  private JLabel lblProgress;
  private JPanel panDetails;
  private JProgressBar prg;
  private int viewRow = -1;
  private DownloadEntry editEntry;
  private final String GAP = "  -  ";
  private Icon icoUnchecked;
  private Icon icoChecked;
  private Icon icoFile;
  @Setter private Consumer<DownloadEntry> onPauseClick;
  @Setter private Consumer<DownloadEntry> onResumeClick;
  @Setter private Consumer<DownloadEntry> onOpenFileClick;
  @Setter private Consumer<DownloadEntry> onOpenFolderClick;
  @Setter private Consumer<DownloadEntry> onDeleteClick;
  @Setter private Consumer<DownloadEntry> onMenuClick;
  private JButton btnOpenFile;
  private JButton btnOpenFolder;
  private JButton btnPause;
  private JButton btnResume;
  private JButton btnDelete;
  private JButton btnMenu;
  private Component pauseGap;
  private Component resumeGap;
  private Component openFileGap;
  private Component openFolderGap;
  private Component buttonContainer;
  private JTable table;
  private MainListViewModel model;
  private int prevSelectionCount;

  public MainListViewRow(JTable table) {
    this(table, null);
  }

  public MainListViewRow(JTable table, MainListViewModel model) {
    this.table = table;
    this.model = model;
    if (model != null) { // Update when cell editor is active
      model.addTableModelListener(
          e -> {
            var r = e.getFirstRow();
            if (r >= table.getRowCount()) {
              return;
            }
            var vr = table.convertRowIndexToView(r);
            if (vr == viewRow && viewRow != -1) {
              var ent = (DownloadEntry) model.getValueAt(r, 0);
              if (ent != null) {
                updateLabelText(ent, table.isRowSelected(vr));
              }
            }
          });
    }

    panel = new JPanel(new BorderLayout(8, 5));
    var p4 = new JPanel(new FlowLayout());
    p4.setOpaque(false);
    var p3 = new JPanel(new BorderLayout());
    p3.setBackground(new Color(30, 144, 255));

    icoUnchecked = AppUtils.createSVGIcon("checkbox-blank-line.svg", 16, Color.WHITE);
    icoChecked = AppUtils.createSVGIcon("checkbox-line.svg", 16, Color.WHITE);
    icoFile = AppUtils.createSVGIcon("file-zip-fill.svg", 16, Color.WHITE);
    icon = new JLabel(icoFile);
    icon.setBorder(new EmptyBorder(7, 7, 7, 7));
    //    icon.addMouseMotionListener(
    //        new MouseAdapter() {
    //          @Override
    //          public void mouseMoved(MouseEvent e) {
    //            if (editEntry != null && editEntry.isSelected()) {
    //              return;
    //            }
    //            icon.setIcon(icoUnchecked);
    //            //            var dim = icon.getSize();
    //            //            var r = new Rectangle(dim.width / 2 - 7, dim.height / 2 - 7, 14,
    // 14);
    //            //            if (r.contains(e.getPoint())) {
    //            //              icon.setIcon(icoChecked);
    //            //            } else {
    //            //              icon.setIcon(icoUnchecked);
    //            //            }
    //          }
    //        });
    icon.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(MouseEvent e) {
            icon.setIcon(table.isRowSelected(viewRow) ? icoChecked : icoUnchecked);
          }

          @Override
          public void mouseExited(MouseEvent e) {
            var isSelectionMode = table.getSelectedRowCount() > 0;
            icon.setIcon(
                table.isRowSelected(viewRow)
                    ? icoChecked
                    : isSelectionMode ? icoUnchecked : icoFile);
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            if (viewRow == -1) {
              return;
            }
            var wasInSelectionMode = table.getSelectedRowCount() > 0;
            table.changeSelection(viewRow, 0, true, false);
            var isInSelectionMode = table.getSelectedRowCount() > 0;
            icon.setIcon(table.isRowSelected(viewRow) ? icoChecked : icoUnchecked);
            updateLabelText(editEntry, table.isRowSelected(viewRow));
            if (wasInSelectionMode != isInSelectionMode && model != null) {
              table.revalidate();
              table.repaint();
            }
          }
        });
    var dim = icon.getPreferredSize();
    p3.putClientProperty(FlatClientProperties.STYLE, "arc: " + dim.width);

    // icon.setOpaque(true);
    // icon.setBackground(Color.ORANGE);

    p3.add(icon);

    //      var chk = new JCheckBox();
    //      chk.setBorder(new EmptyBorder(0, 0, 0, 8));
    //      p4.add(chk);
    p4.add(p3);
    // p3.setBorder(new EmptyBorder(5,5,5,5));

    var content = new JPanel(new GridLayout(2, 1, 0, 0));
    content.setOpaque(false);

    lblTitle = new JLabel("Some long title name for testing");
    lblTitle.setVerticalAlignment(SwingConstants.BOTTOM);
    var fnt = lblTitle.getFont().deriveFont(12.0f);
    lblInfo = new JLabel("Some long title name for testing");
    lblInfo.setVerticalAlignment(SwingConstants.TOP);
    lblInfo.setFont(fnt);
    lblInfo.setFont(lblTitle.getFont().deriveFont(12.0f));
    lblInfo.setForeground(Color.GRAY);
    content.add(lblTitle);
    content.add(lblInfo);

    var p1 = new JPanel(new BorderLayout(0, 0));
    panDetails = new JPanel(new BorderLayout());
    panDetails.setBorder(new EmptyBorder(0, 0, 0, 10));
    prg = new JProgressBar();
    prg.setPreferredSize(new Dimension(50, 10));
    prg.setAlignmentY(Component.TOP_ALIGNMENT);
    lblProgress = new JLabel("Sample text");
    lblProgress.setFont(fnt);
    panDetails.add(lblProgress);
    panDetails.add(prg, BorderLayout.SOUTH);
    p1.add(panDetails);
    prg.setBorder(new EmptyBorder(0, 0, 5, 0));

    var buttonContainer = Box.createHorizontalBox();
    buttonContainer.setBorder(new EmptyBorder(10, 0, 10, 0));

    btnPause =
        createButton(
            "pause-circle-line.svg",
            e -> {
              if (onPauseClick != null) {
                onPauseClick.accept(editEntry);
              }
            });

    btnResume =
        createButton(
            "play-circle-line.svg",
            e -> {
              if (onResumeClick != null) {
                onResumeClick.accept(editEntry);
              }
            });

    btnDelete =
        createButton(
            "delete-bin-line.svg",
            e -> {
              if (onDeleteClick != null) {
                onDeleteClick.accept(editEntry);
              }
            });

    btnMenu =
        createButton(
            "more-2-fill.svg",
            e -> {
              if (onMenuClick != null) {
                onMenuClick.accept(editEntry);
              }
            });

    btnOpenFile =
        createButton(
            "share-box-line.svg",
            e -> {
              if (onOpenFileClick != null) {
                onOpenFileClick.accept(editEntry);
              }
            });

    btnOpenFolder =
        createButton(
            "folder-6-line.svg",
            e -> {
              if (onOpenFolderClick != null) {
                onOpenFolderClick.accept(editEntry);
              }
            });

    pauseGap = Box.createRigidArea(new Dimension(5, 10));
    resumeGap = Box.createRigidArea(new Dimension(5, 10));
    openFileGap = Box.createRigidArea(new Dimension(5, 10));
    openFolderGap = Box.createRigidArea(new Dimension(5, 10));

    buttonContainer.add(btnPause);
    buttonContainer.add(pauseGap);
    buttonContainer.add(btnResume);
    buttonContainer.add(resumeGap);
    buttonContainer.add(btnOpenFolder);
    buttonContainer.add(openFolderGap);
    buttonContainer.add(btnOpenFile);
    buttonContainer.add(openFileGap);
    buttonContainer.add(btnDelete);
    buttonContainer.add(Box.createRigidArea(new Dimension(2, 10)));
    buttonContainer.add(btnMenu);

    p1.add(buttonContainer, BorderLayout.EAST);
    p1.add(panDetails);
    p1.setOpaque(false);
    p4.setOpaque(false);

    panel.add(p4, BorderLayout.WEST);
    panel.add(content);
    panel.add(p1, BorderLayout.EAST);
    panel.setBorder(new EmptyBorder(0, 5, 5, 5));

    this.buttonContainer = buttonContainer;
  }

  private JButton createButton(String iconName, ActionListener e) {
    var btn = new JButton(AppUtils.createSVGIcon(iconName, 16, Color.GRAY));
    btn.putClientProperty("JButton.buttonType", "toolBarButton");
    btn.addActionListener(e);
    return btn;
  }

  public int getHeight() {
    return panel.getPreferredSize().height;
  }

  public void showMenu(JPopupMenu menu) {
    AppUtils.showMenu(btnMenu, menu);
  }

  private void updateLabelText(DownloadEntry ent, boolean isSelected) {
    icon.setIcon(
        isSelected ? icoChecked : table.getSelectedRowCount() > 0 ? icoUnchecked : icoFile);
    buttonContainer.setVisible(this.table.getSelectedRowCount() == 0);
    btnOpenFile.setVisible(ent.getState() == DownloadEntryState.FINISHED);
    openFileGap.setVisible(btnOpenFile.isVisible());
    btnOpenFolder.setVisible(ent.getState() == DownloadEntryState.FINISHED);
    openFolderGap.setVisible(btnOpenFolder.isVisible());
    btnPause.setVisible(ent.getState() == DownloadEntryState.DOWNLOADING);
    pauseGap.setVisible(btnPause.isVisible());
    btnResume.setVisible(
        ent.getState() != DownloadEntryState.FINISHED
            && ent.getState() != DownloadEntryState.DOWNLOADING);
    resumeGap.setVisible(btnResume.isVisible());
    if (ent.getState() == DownloadEntryState.FINISHED) {
      lblInfo.setText(
          FormatUtilities.formatDateShort(ent.getDateEpoch())
              + GAP
              + FormatUtilities.formatSize(ent.getSize()));
      lblTitle.setText(ent.getFileName());
      prg.setVisible(false);
      lblProgress.setText("");
    } else {
      var text = new StringBuilder(80);
      text.append(FormatUtilities.formatDateShort(ent.getDateEpoch()));
      if (ent.getDownloaded() > 0) {
        text.append(GAP).append(FormatUtilities.formatSize(ent.getDownloaded()));
      }
      if (ent.getSize() > 0) {
        text.append(" / ").append(FormatUtilities.formatSize(ent.getSize()));
      }
      if (ent.getSpeed() > 0) {
        text.append(" (").append(FormatUtilities.formatSize(ent.getSpeed())).append("/s)");
      }
      if (ent.getEta() > 0) {
        text.append(GAP).append(FormatUtilities.toLongEta(ent.getEta())).append(" left");
      }
      lblInfo.setText(text.toString());
      lblTitle.setText(ent.getFileName());
      prg.setVisible(true);
      prg.setValue(ent.getProgress());
      var prgText = StringResource.get("STAT_DOWNLOADING");
      if (ent.getState() == DownloadEntryState.DOWNLOADING) {
        prgText =
            String.format(
                "%s %d%s", StringResource.get("STAT_DOWNLOADING"), ent.getProgress(), "%");
      } else if (ent.getState() == DownloadEntryState.PAUSED) {
        prgText =
            String.format("%s %d%s", StringResource.get("STAT_PAUSED"), ent.getProgress(), "%");
      } else if (ent.getState() == DownloadEntryState.ASSEMBLING) {
        prgText = StringResource.get("STAT_ASSEMBLING");
      }
      lblProgress.setText(prgText);
    }
  }

  private Component getComp(JTable table, DownloadEntry value, boolean isSelected) {
    updateLabelText(value, isSelected);
    if (table != null) {
      panel.setBackground(table.getBackground());
    }
    return panel;
  }

  @Override
  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    this.editEntry = null;
    this.viewRow = -1;
    return getComp(table, (DownloadEntry) value, isSelected);
  }

  @Override
  public Component getTableCellEditorComponent(
      JTable table, Object value, boolean isSelected, int row, int column) {
    this.editEntry = (DownloadEntry) value;
    this.viewRow = row;
    return getComp(table, (DownloadEntry) value, isSelected);
  }

  @Override
  public Object getCellEditorValue() {
    return 99;
  }

  @Override
  public boolean isCellEditable(EventObject anEvent) {
    return true;
  }

  @Override
  public boolean shouldSelectCell(EventObject anEvent) {
    return false;
  }

  @Override
  public boolean stopCellEditing() {
    viewRow = -1;
    return true;
  }

  @Override
  public void cancelCellEditing() {
    viewRow = -1;
  }

  @Override
  public void addCellEditorListener(CellEditorListener l) {
    // Noop
  }

  @Override
  public void removeCellEditorListener(CellEditorListener l) {
    // Noop
  }
}
