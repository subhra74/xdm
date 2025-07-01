package xdm.app.util;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import java.awt.*;
import java.io.IOException;

public class AppUtils {
  public static FlatSVGIcon createSVGIcon(String name, int size, Color color) {
    FlatSVGIcon.ColorFilter filter = new FlatSVGIcon.ColorFilter();
    filter.add(Color.BLACK, color);
    try {
      var icon = new FlatSVGIcon(AppUtils.class.getResourceAsStream("/icons/" + name));
      icon.setColorFilter(filter);
      return icon.derive(size, size);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
