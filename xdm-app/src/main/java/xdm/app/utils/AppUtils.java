package xdm.app.utils;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.*;
import java.io.IOException;

public class AppUtils {
  public static FlatSVGIcon createSVGIcon(String name, int size, Color color) {
    FlatSVGIcon.ColorFilter filter = new FlatSVGIcon.ColorFilter();
    filter.add(Color.BLACK, color);
    try {
      // var icon = new FlatSVGIcon(AppUtils.class.getResourceAsStream("/icons/" + name), size,
      // size);
      var icon = new FlatSVGIcon("icons/" + name, size, size);
      icon.setColorFilter(filter);
      // return icon.derive(size, size);
      return icon;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static FlatSVGIcon createSVGIcon(String name, int size) {
    try {
      var icon = new FlatSVGIcon(AppUtils.class.getResourceAsStream("/icons/" + name));
      return icon.derive(size, size);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static void sameWidth(Component c1, Component c2) {
    var p1 = c1.getPreferredSize();
    var p2 = c2.getPreferredSize();
    var maxW = Math.max(p1.width, p2.width);
    var maxH = Math.max(p1.height, p2.height);
    var dim = new Dimension(maxW, maxH);
    c1.setPreferredSize(dim);
    c2.setPreferredSize(dim);
  }
}
