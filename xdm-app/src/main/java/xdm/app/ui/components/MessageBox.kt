package xdm.app.ui.components

import xdm.app.MessageBoxResult
import java.awt.Window
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.JPasswordField

data class AuthInput(val userName: String, val password: String)

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

    fun showAuth(title: String, message: String): AuthInput? {
        val usernameField = JTextField()
        val passwordField = JPasswordField()

        val components = mutableListOf<Any>(
            message,
            "Username:", usernameField,
            "Password:", passwordField
        )

        val result = JOptionPane.showOptionDialog(
            null, // Parent component, can be null for a default frame
            components.toTypedArray(),
            title,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            null,
            null
        )

        return if (result == JOptionPane.OK_OPTION) {
            AuthInput(
                userName = usernameField.text,
                password = String(passwordField.password),
            )
        } else null
    }
}
