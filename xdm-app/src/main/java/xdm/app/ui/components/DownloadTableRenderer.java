package xdm.app.ui.components;

import xdm.app.constants.DownloadEntryState;
// import xdm.app.models.DownloadEntry;
import xdm.app.data.DbRecord;
import xdm.app.data.RecordStatus;
import xdm.app.utils.AppUtils;
import xdman.ui.res.StringResource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import static xdman.util.FormatUtilities.formatSize;

public class DownloadTableRenderer implements TableCellRenderer {
  private final JLabel lblIconText;
  private final JLabel lblTextOnly;
  private final JLabel lblProgress;
  private final JProgressBar prgProgress;
  private final JPanel panProgress;
  private final SimpleDateFormat formatShort = new SimpleDateFormat("MMM dd");
  private final SimpleDateFormat formatLong = new SimpleDateFormat("yy-MM-dd");
  private final Date oneYearAgo;

  public DownloadTableRenderer() {
    lblIconText = new JLabel("Some text");
    lblIconText.setIcon(AppUtils.createSVGIcon("file-zip-fill.svg", 24, Color.GRAY));
    lblIconText.setIconTextGap(10);
    lblIconText.setOpaque(true);
    lblIconText.setBorder(new EmptyBorder(8, 10, 6, 5));

    lblTextOnly = new JLabel("Some text");
    lblTextOnly.setOpaque(true);
    lblTextOnly.setBorder(new EmptyBorder(8, 5, 6, 5));

    lblProgress = new JLabel("Some text");
    //    lblProgress.setVerticalAlignment(SwingConstants.BOTTOM);

    prgProgress = new JProgressBar();
    prgProgress.setPreferredSize(new Dimension(10, 3));

    panProgress = new JPanel(new BorderLayout(0, 0));
    panProgress.setBorder(new EmptyBorder(0, 5, 0, 5));
    panProgress.add(lblProgress);
    panProgress.add(prgProgress, BorderLayout.SOUTH);

    Calendar cal = Calendar.getInstance();
    cal.setTime(new Date());
    cal.add(Calendar.YEAR, -1);
    oneYearAgo = cal.getTime();
  }

  public int getCellHeight() {
    return Math.min(lblIconText.getPreferredSize().height, lblTextOnly.getPreferredSize().height);
  }

  @Override
  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    var ent = (DbRecord) value;
    var label = column == 0 ? lblIconText : lblTextOnly;
    String text;
    switch (column) {
      case 0:
        text = ent.getFileName();
        break;
      case 1:
        text = formatSize(ent.getSize());
        break;
      case 2:
        var date = new Date(ent.getDate());
        text = date.before(oneYearAgo) ? formatLong.format(date) : formatShort.format(date);
        break;
      case 3:
        if (ent.getStatus() == RecordStatus.FINISHED) {
          text = StringResource.get("STAT_FINISHED");
        } else if (ent.getStatus() == RecordStatus.DOWNLOADING) {
          lblProgress.setText(
              String.format(
                  "%s %d%s", StringResource.get("STAT_DOWNLOADING"), ent.getProgress(), "%"));
          prgProgress.setValue(ent.getProgress());
          panProgress.setBackground(
              isSelected ? table.getSelectionBackground() : table.getBackground());
          return panProgress;
        } else if (ent.getStatus() == RecordStatus.PAUSED) {
          text =
              String.format("%s %d%s", StringResource.get("STAT_PAUSED"), ent.getProgress(), "%");
        } else if (ent.getStatus() == RecordStatus.ASSEMBLING) {
          text = StringResource.get("STAT_ASSEMBLING");
        } else {
          text = StringResource.get("STAT_DOWNLOADING");
        }
        break;
      default:
        text = "";
    }
    label.setText(text);
    label.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
    return label;
  }
}
