package xdm.app.utils

import xdm.app.I8N.text
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.text.DefaultEditorKit
import javax.swing.text.JTextComponent

/**
 * Installs a single global cut/copy/paste/select-all context menu for every
 * [JTextComponent] in the app (all `JTextField`, `JTextArea`, `JPasswordField`,
 * etc.), including ones created after install.
 *
 * Rather than attaching a popup to each field individually, we listen for the
 * platform popup-trigger on the AWT event queue and build the menu on demand for
 * whichever text component was clicked. Menu items reuse the built-in
 * [DefaultEditorKit] actions so they honour selection, editability and system
 * clipboard state automatically.
 */
object TextContextMenu {

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
    }

    private val listener = AWTEventListener { event ->
        if (event !is MouseEvent || !event.isPopupTrigger) return@AWTEventListener
        val target = event.component as? JTextComponent ?: return@AWTEventListener
        if (!target.isEnabled) return@AWTEventListener

        // Focus the field first so the editor actions operate on it.
        if (!target.hasFocus()) target.requestFocusInWindow()

        buildMenu(target).show(target, event.x, event.y)
        event.consume()
    }

    private fun buildMenu(target: JTextComponent): JPopupMenu {
        val editable = target.isEditable
        val hasSelection = target.selectionStart != target.selectionEnd
        val hasText = target.document.length > 0
        val clipboardHasText = clipboardHasText()

        return JPopupMenu().apply {
            add(item(text("CTX_CUT"), DefaultEditorKit.cutAction, target, editable && hasSelection))
            add(item(text("CTX_COPY"), DefaultEditorKit.copyAction, target, hasSelection))
            add(item(text("CTX_PASTE"), DefaultEditorKit.pasteAction, target, editable && clipboardHasText))
            addSeparator()
            add(item(text("CTX_SELECT_ALL"), DefaultEditorKit.selectAllAction, target, hasText))
        }
    }

    private fun item(label: String, actionName: String, target: JTextComponent, enabled: Boolean): JMenuItem {
        return JMenuItem(label).apply {
            isEnabled = enabled
            addActionListener {
                val action = target.actionMap[actionName] ?: return@addActionListener
                action.actionPerformed(java.awt.event.ActionEvent(target, java.awt.event.ActionEvent.ACTION_PERFORMED, actionName))
            }
        }
    }

    private fun clipboardHasText(): Boolean = try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)
    } catch (e: Exception) {
        // Some platforms deny clipboard access; assume paste is available.
        true
    }
}
