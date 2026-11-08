package xdm.app.ui.screens.settings

import xdm.app.CommandRule
import xdm.app.I8N
import xdm.app.ui.components.MessageBox
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Window
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Add/edit dialog for one [CommandRule]: the file types it applies to and the command, with the
 * placeholders listed underneath. Settings opens it modal (a child of the settings window) and reads
 * [result] afterwards, null when cancelled. Whether the rule is on stays as it was; the row's switch sets it.
 */
class CommandRuleDialog(parent: Window, private val existing: CommandRule?) : JDialog(parent) {
    var result: CommandRule? = null
        private set

    // Columns: a long command must not widen the dialog; the grid stretches the fields.
    private val txtTypes = rounded(JTextField()).apply { columns = 10; fixHeight(this) }
    private val txtCommand = rounded(JTextField()).apply { columns = 10; fixHeight(this) }

    init {
        title = I8N.text(if (existing == null) "CMD_ADD_RULE" else "CMD_EDIT_RULE")
        isModal = true
        layout = BorderLayout()

        existing?.let {
            txtTypes.text = it.extensions.joinToString(", ")
            txtCommand.text = it.command
        }
        txtTypes.putClientProperty("JTextField.placeholderText", I8N.text("CMD_FILE_TYPES_PLACEHOLDER"))
        txtTypes.toolTipText = I8N.text("CAT_FILE_TYPES_HINT")
        txtCommand.putClientProperty("JTextField.placeholderText", I8N.text("MSG_CUSTOM_CMD"))

        val form = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(14, 14, 6, 14) }
        var row = 0
        fun addRow(label: String?, field: Component, top: Int = 4) {
            label?.let {
                form.add(JLabel(it), GridBagConstraints().apply {
                    gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHEAST
                    insets = scaledInsets(top + 5, 0, 4, 10)
                })
            }
            form.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST; insets = scaledInsets(top, 0, 4, 0)
            })
            row++
        }
        addRow(I8N.text("CMD_FILE_TYPES"), txtTypes)
        addRow(I8N.text("CMD_COMMAND"), txtCommand)
        addRow(null, placeholderTable(), top = 10)

        val btnOk = settingsButton(I8N.text("DESC_SAVE_Q")) { onOk() }
        val btnCancel = settingsButton(I8N.text("ND_CANCEL")) { dispose() }
        rootPane.defaultButton = btnOk
        val buttons = Box.createHorizontalBox().apply {
            add(Box.createHorizontalGlue())
            add(btnOk)
            add(Box.createRigidArea(scaledSize(10, 10)))
            add(btnCancel)
            padding(this, 10, topPadding = true)
            add(Box.createRigidArea(scaledSize(10, 10)))
        }

        add(form, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        pack()
        size = Dimension(maxOf(width, 560.px), height)
        setLocationRelativeTo(parent)
    }

    /** Muted two-column list: each placeholder and what it becomes, then the no-placeholder rule. */
    private fun placeholderTable(): JComponent {
        val panel = JPanel(GridBagLayout()).apply { isOpaque = false }
        fun muted(text: String, bold: Boolean = false) = JLabel(text).apply {
            foreground = settingsMutedColor()
            font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, font.size2D - 1f)
        }
        var y = 0
        panel.add(muted(I8N.text("CMD_PLACEHOLDERS"), bold = true), GridBagConstraints().apply {
            gridx = 0; gridy = y++; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = scaledInsets(0, 0, 4, 0)
        })
        for ((token, key) in CommandRuleSection.PLACEHOLDER_KEYS) {
            panel.add(muted(token), GridBagConstraints().apply {
                gridx = 0; gridy = y; anchor = GridBagConstraints.WEST; insets = scaledInsets(1, 0, 1, 12)
            })
            panel.add(muted(I8N.text(key)), GridBagConstraints().apply {
                gridx = 1; gridy = y++; anchor = GridBagConstraints.WEST; weightx = 1.0
            })
        }
        panel.add(muted(I8N.text("CMD_PH_NONE")), GridBagConstraints().apply {
            gridx = 0; gridy = y; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = scaledInsets(6, 0, 0, 0)
        })
        return panel
    }

    private fun onOk() {
        val extensions = CommandRule.normalizeExtensions(txtTypes.text)
        if (extensions.isEmpty()) {
            showError(I8N.text("MSG_CMD_TYPES_MISSING"))
            return
        }
        val command = txtCommand.text.trim()
        if (command.isEmpty()) {
            showError(I8N.text("MSG_CMD_COMMAND_MISSING"))
            return
        }
        result = CommandRule(extensions, command, existing?.enabled ?: true)
        dispose()
    }

    private fun showError(message: String) =
        MessageBox.show(this, title, message, JOptionPane.WARNING_MESSAGE)
}
