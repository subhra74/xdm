package xdm.app.ui.components;

import xdm.app.util.AppUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

public class CategoryTreeCellRenderer implements TreeCellRenderer {
  private JLabel label;

  public CategoryTreeCellRenderer() {
    label = new JLabel();
  }

  @Override
  public Component getTreeCellRendererComponent(
      JTree tree,
      Object value,
      boolean selected,
      boolean expanded,
      boolean leaf,
      int row,
      boolean hasFocus) {
    label.setText(value.toString());
    label.setIcon(AppUtils.createSVGIcon("file-zip-fill.svg", 20, Color.GRAY));
    label.setIconTextGap(10);
    label.setOpaque(true);
    label.setBackground(
        selected ? UIManager.getColor("Tree.selectionBackground") : tree.getBackground());
    label.setForeground(
        selected ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground());
    label.setBorder(new EmptyBorder(5, 5, 5, 5));
    return label;
  }
}
