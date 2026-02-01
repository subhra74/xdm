package xdm.app.ui.components;

import xdm.app.models.FilterListItem;
import xdm.app.utils.AppUtils;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;

public class FilterListRenderer implements ListCellRenderer<FilterListItem> {
  private final Border border, selectedBorder;
  private final JLabel label;

  public FilterListRenderer() {
    border = new EmptyBorder(7, 20, 7, 15);
    selectedBorder =
        new CompoundBorder(
            new EmptyBorder(7, 0, 7, 0),
            new CompoundBorder(
                new MatteBorder(0, 3, 0, 0, new Color(30, 144, 255)),
                new EmptyBorder(0, 17, 0, 15)));
    label = new JLabel();
    label.setIcon(AppUtils.createSVGIcon("arrow-up-down-fill.svg", 20, Color.GRAY));
    label.setIconTextGap(10);
    label.setBorder(border);
  }

  @Override
  public Component getListCellRendererComponent(
      JList<? extends FilterListItem> list,
      FilterListItem value,
      int index,
      boolean isSelected,
      boolean cellHasFocus) {
    label.setText(value.getText());
    label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
    label.setIcon(isSelected ? value.getSelectedIcon() : value.getIcon());
    label.setBorder(isSelected ? selectedBorder : border);
    return label;
  }
}
