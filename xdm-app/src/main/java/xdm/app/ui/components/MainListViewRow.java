package xdm.app.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import xdm.app.constants.DownloadEntryState;
import xdm.app.models.DownloadEntry;
import xdm.app.utils.AppUtils;
import xdm.core.util.FormatUtilities;
import xdman.ui.res.StringResource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.CellEditorListener;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.EventObject;

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

  public MainListViewRow(JTable table, MainTableModel model) {
    if (model != null) { // Update when cell editor is active
      model.addTableModelListener(
          e -> {
            var r = e.getFirstRow();
            System.out.println("r: "+r+" row count: "+table.getRowCount());
            if(r>=table.getRowCount()){
              return;
            }
            var vr = table.convertRowIndexToView(r);
            if (vr == viewRow && viewRow != -1) {
              var ent = (DownloadEntry) model.getValueAt(r, 0);
              if (ent != null) {
                updateLabelText(ent);
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
    icon.addMouseMotionListener(
        new MouseAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            if (editEntry != null && editEntry.isSelected()) {
              return;
            }
            icon.setIcon(icoUnchecked);
            //            var dim = icon.getSize();
            //            var r = new Rectangle(dim.width / 2 - 7, dim.height / 2 - 7, 14, 14);
            //            if (r.contains(e.getPoint())) {
            //              icon.setIcon(icoChecked);
            //            } else {
            //              icon.setIcon(icoUnchecked);
            //            }
          }
        });
    icon.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(MouseEvent e) {
            if (editEntry != null && editEntry.isSelected()) {
              return;
            }
            icon.setIcon(icoUnchecked);
          }

          @Override
          public void mouseExited(MouseEvent e) {
            if (editEntry != null && editEntry.isSelected()) {
              return;
            }
            icon.setIcon(icoFile);
          }

          @Override
          public void mouseClicked(MouseEvent e) {
            if (editEntry == null) {
              return;
            }
            editEntry.setSelected(!editEntry.isSelected());
            if (editEntry.isSelected()) {
              icon.setIcon(icoChecked);
            } else {
              icon.setIcon(icoUnchecked);
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

    var p1 = new JPanel(new BorderLayout(10, 0));
    panDetails = new JPanel(new BorderLayout());
    prg = new JProgressBar();
    prg.setPreferredSize(new Dimension(50, 10));
    prg.setAlignmentY(Component.TOP_ALIGNMENT);
    lblProgress = new JLabel("Sample text");
    lblProgress.setFont(fnt);
    panDetails.add(lblProgress);
    panDetails.add(prg, BorderLayout.SOUTH);
    p1.add(panDetails);
    prg.setBorder(new EmptyBorder(0, 0, 5, 0));

    var actions = Box.createHorizontalBox();
    actions.setBorder(new EmptyBorder(10, 10, 10, 10));
    var b1 = new JButton(AppUtils.createSVGIcon("pause-circle-line.svg", 16, Color.GRAY));
    b1.putClientProperty("JButton.buttonType", "toolBarButton");
    b1.addActionListener(
        e -> {
          System.out.println("Clicked: " + viewRow);
        });
    var b2 = new JButton(AppUtils.createSVGIcon("delete-bin-line.svg", 16, Color.GRAY));
    b2.putClientProperty("JButton.buttonType", "toolBarButton");
    actions.add(
        b1); // new JLabel(AppUtils.createSVGIcon("pause-circle-line.svg", 16, Color.GRAY)));
    actions.add(Box.createRigidArea(new Dimension(10, 10)));
    actions.add(b2); // new JLabel(AppUtils.createSVGIcon("delete-bin-line.svg", 16, Color.GRAY)));
    actions.add(Box.createRigidArea(new Dimension(5, 10)));
    // actions.add(new JLabel(AppUtils.createSVGIcon("more-2-fill.svg", 20, Color.GRAY)));

    p1.add(actions, BorderLayout.EAST);
    p1.add(panDetails);
    p1.setOpaque(false);
    p4.setOpaque(false);

    panel.add(p4, BorderLayout.WEST);
    panel.add(content);
    panel.add(p1, BorderLayout.EAST);
    panel.setBorder(new EmptyBorder(0, 5, 5, 0));
  }

  public int getHeight() {
    return panel.getPreferredSize().height;
  }

  //  private JLabel createStatusLabel(String iconName, String text, Font font) {
  //    var l1 = new JLabel(AppUtils.createSVGIcon(iconName, 12, Color.GRAY));
  //    l1.setFont(font);
  //    l1.setForeground(Color.GRAY);
  //    l1.setText(text);
  //    return l1;
  //  }

  private void updateLabelText(DownloadEntry ent) {
    if (ent.isSelected()) {
      icon.setIcon(icoChecked);
    } else {
      icon.setIcon(icoFile);
    }
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

  private Component getComp(JTable table, DownloadEntry value) {
    updateLabelText(value);
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
    return getComp(table, (DownloadEntry) value);
  }

  @Override
  public Component getTableCellEditorComponent(
      JTable table, Object value, boolean isSelected, int row, int column) {
    this.editEntry = (DownloadEntry) value;
    this.viewRow = row;
    return getComp(table, (DownloadEntry) value);
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
