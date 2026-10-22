package xdm.app.ui.components

import xdm.app.MessageBoxResult
import xdm.app.utils.shownWindow
import xdm.app.utils.windowIconImages
import java.awt.Component
import java.awt.Dialog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JOptionPane
import javax.swing.JTextField
import javax.swing.JPasswordField
import javax.swing.SwingUtilities
import javax.swing.UIManager

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

    fun confirm(window: Window?, title: String?, message: String?): Boolean {
        return (JOptionPane.showConfirmDialog(window, message, title, JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION)
    }

    /** Info messages on screen, by owner and message: asking again brings the open one forward. EDT only. */
    private val openMessages = mutableMapOf<Pair<Window?, Any?>, JDialog>()

    /**
     * Shows a message that only informs, without blocking: the caller carries on at once and every
     * other window stays usable. A modal one would hold up progress and new-download windows too,
     * and app-modal is the only modality native Wayland has. Callable from any thread.
     *
     * The message closes with its owner, so a window that disposes itself right after showing one
     * passes null. A null [title] gives JOptionPane's usual one.
     */
    fun show(
        parent: Component?, title: String?, message: Any?, messageType: Int = JOptionPane.INFORMATION_MESSAGE
    ) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { show(parent, title, message, messageType) }
            return
        }
        val owner = shownWindow(parent)
        val key = owner to message
        openMessages[key]?.let { it.toFront(); return }
        val pane = JOptionPane(message, messageType)
        val dialogTitle = title ?: UIManager.getString("OptionPane.messageDialogTitle", (owner ?: pane).locale)
        // createDialog(title) is truly ownerless, with its own taskbar button on Windows; a null
        // parent would hang it on Swing's hidden shared frame, which has none.
        val dialog = if (owner != null) pane.createDialog(owner, dialogTitle)
        else pane.createDialog(dialogTitle).apply { iconImages = windowIconImages }
        dialog.modalityType = Dialog.ModalityType.MODELESS
        // Usable while a modal dialog elsewhere is open. Native Wayland has no modal exclusion and
        // ignores this; there it waits for that dialog.
        dialog.modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE
        // The pane only hides its dialog (OK, Esc, close box); nothing else disposes a modeless one.
        dialog.addComponentListener(object : ComponentAdapter() {
            override fun componentHidden(e: ComponentEvent) = dialog.dispose()
        })
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                openMessages.remove(key)
            }
        })
        openMessages[key] = dialog
        dialog.isVisible = true
    }

    /**
     * Asks for a user name and password in a dialog of its own: no owner, so it has a taskbar button
     * on Windows and shows while the main window is in the tray. [modal] only for a caller on the EDT,
     * which has to wait inside the dialog; otherwise it blocks no window, the progress windows
     * included, and [onDone] gets the answer (null when cancelled). EDT only.
     */
    fun showAuth(title: String, message: String, modal: Boolean, onDone: (AuthInput?) -> Unit) {
        val usernameField = JTextField()
        val passwordField = JPasswordField()
        val pane = JOptionPane(
            arrayOf<Any>(message, "Username:", usernameField, "Password:", passwordField),
            JOptionPane.QUESTION_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
        )
        val dialog = pane.createDialog(title).apply { iconImages = windowIconImages }
        if (!modal) {
            dialog.modalityType = Dialog.ModalityType.MODELESS
            // As for info messages; ignored on native Wayland.
            dialog.modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE
        }
        var answered = false
        val finish = {
            if (!answered) {
                answered = true
                onDone(
                    if (pane.value == JOptionPane.OK_OPTION) AuthInput(usernameField.text, String(passwordField.password))
                    else null
                )
            }
        }
        dialog.addComponentListener(object : ComponentAdapter() {
            override fun componentHidden(e: ComponentEvent) {
                finish()
                dialog.dispose()
            }
        })
        dialog.isVisible = true
        // Modal: the answer is in once setVisible returns.
        if (modal) {
            finish()
            dialog.dispose()
        }
    }
}
