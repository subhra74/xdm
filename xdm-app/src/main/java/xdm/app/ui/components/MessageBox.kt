package xdm.app.ui.components

import xdm.app.MessageBoxResult
import xdm.app.utils.windowIconImages
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
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

    fun confirm(window: JFrame?, title: String?, message: String?): Boolean {
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
        val owner = visibleOwner(parent)
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
     * [parent]'s window, or null when it is hidden or minimized (the main window in the tray): an
     * owned window has no taskbar button on Windows, Windows hides it along with a minimized owner,
     * and native Wayland gives it no parent surface unless the owner is on screen.
     */
    private fun visibleOwner(parent: Component?): Window? {
        val window = parent as? Window ?: parent?.let { SwingUtilities.getWindowAncestor(it) } ?: return null
        if (!window.isShowing) return null
        val minimized = generateSequence(window) { it.owner }
            .any { it is Frame && (it.extendedState and Frame.ICONIFIED) != 0 }
        return window.takeUnless { minimized }
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
