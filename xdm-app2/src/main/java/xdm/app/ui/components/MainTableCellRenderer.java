package xdm.app.ui.components;

import xdm.app.util.AppUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import java.awt.*;

public class MainTableCellRenderer implements TableCellRenderer {
  private final JLabel iconTextLabel;
  private final JLabel textLabel;

  public MainTableCellRenderer() {
    iconTextLabel = new JLabel();
    iconTextLabel.setIcon(AppUtils.createSVGIcon("file-zip-fill.svg", 20, Color.GRAY));
    iconTextLabel.setText("Some ");
    iconTextLabel.setIconTextGap(10);
    iconTextLabel.setOpaque(true);
    iconTextLabel.setBorder(new EmptyBorder(5, 10, 5, 5));

    textLabel = new JLabel("Some text");
    textLabel.setOpaque(true);
    textLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
  }

  public int getPreferredHeight() {
    return this.iconTextLabel.getPreferredSize().height;
  }

  @Override
  public Component getTableCellRendererComponent(
      JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
    switch (column) {
      case 0:
        iconTextLabel.setText("Some text with icon");
        iconTextLabel.setIconTextGap(10);
        iconTextLabel.setBackground(
            isSelected ? table.getSelectionBackground() : table.getBackground());
        return iconTextLabel;
      default:
        textLabel.setText("Some text");
        textLabel.setBackground(
            isSelected ? table.getSelectionBackground() : table.getBackground());
        return textLabel;
    }
  }
}
