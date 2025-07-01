package xdm.app.ui.components;

import com.formdev.flatlaf.ui.FlatRoundBorder;
import xdm.app.utils.AppUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

public class FilterTreeCellRenderer implements TreeCellRenderer {

  @Override
  public Component getTreeCellRendererComponent(
      JTree tree,
      Object value,
      boolean selected,
      boolean expanded,
      boolean leaf,
      int row,
      boolean hasFocus) {
    var lbl = new JLabel(value.toString());
    lbl.setIcon(AppUtils.createSVGIcon("file-zip-fill.svg", 24, Color.GRAY));
    lbl.setIconTextGap(10);
    lbl.setOpaque(true);
    lbl.setBackground(
        selected ? UIManager.getColor("Tree.selectionBackground") : tree.getBackground());
    lbl.setForeground(
        selected ? UIManager.getColor("Tree.selectionForeground") : tree.getForeground());
    lbl.setBorder(new EmptyBorder(5, 5, 5, 5));
    return lbl;
  }
}
