package xdm.app.ui.components;

import xdman.constants.MessageBoxResult;

import javax.swing.*;
import java.awt.*;

public class MessageBox {
  public static MessageBoxResult confirmWithCheckBox(
      Window window, String title, String message, String chkMessage) {
    var chk = new JCheckBox(chkMessage);
    if (JOptionPane.showOptionDialog(
            window,
            new Object[] {message, chk},
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            null,
            null)
        == JOptionPane.YES_OPTION) {
      return chk.isSelected() ? MessageBoxResult.YES_WITH_SELECTION : MessageBoxResult.YES;
    }
    return MessageBoxResult.CANCEL;
  }

  public static boolean confirm(JFrame window, String title, String message) {
    return JOptionPane.showConfirmDialog(window, message, title, JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }

  public static void show(Window window, String title, String message) {
    JOptionPane.showMessageDialog(window, message, title, JOptionPane.INFORMATION_MESSAGE);
  }
}
