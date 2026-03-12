package xdm.app.ui.components

import xdm.app.MessageBoxResult
import java.awt.Window
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JOptionPane

object MessageBox {
    fun confirmWithCheckBox(
        window: Window?, title: String?, message: String, chkMessage: String?
    ): MessageBoxResult {
        val chk = JCheckBox(chkMessage)
        if (JOptionPane.showOptionDialog(
                window,
                arrayOf<Any>(message, chk),
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                null,
                null
            )
            == JOptionPane.YES_OPTION
        ) {
            return if (chk.isSelected) MessageBoxResult.YES_WITH_SELECTION else MessageBoxResult.YES
        }
        return MessageBoxResult.CANCEL
    }

    fun confirm(window: JFrame?, title: String?, message: String?): Boolean {
        return (JOptionPane.showConfirmDialog(window, message, title, JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION)
    }

    fun show(window: Window?, title: String?, message: String?) {
        JOptionPane.showMessageDialog(window, message, title, JOptionPane.INFORMATION_MESSAGE)
    }
}
