package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.CommandRule
import xdm.app.I8N
import xdm.app.PostDownloadCommand
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import xdm.app.utils.scaledSize
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * The "Run commands after download" card on the Advanced page: per-extension [CommandRule]s, tried top
 * to bottom, then the catch-all command for every other file and for batch downloads. Edits a working
 * copy; [save] pushes it into the config, so cancelling the settings window discards the edits.
 */
class CommandRuleSection {
    private val rules = mutableListOf<CommandRule>()

    private val tglRunCmd = SettingsToggle()
    private val txtCmd = rounded(JTextField()).apply {
        putClientProperty("JTextField.placeholderText", I8N.text("MSG_CUSTOM_CMD"))
        columns = 10
    }

    private val rows = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val emptyHint = settingsHint(I8N.text("CMD_EMPTY")).apply { isVisible = false }

    /** The card to drop into a settings panel. */
    val component: JComponent by lazy {
        val list = Box.createVerticalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsDescription(I8N.text("CMD_DESC")))
            add(Box.createRigidArea(scaledSize(0, 4)))
            add(rows)
            add(settingsLeftAligned(emptyHint))
        }
        val footer = Box.createHorizontalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsButton(I8N.text("CMD_ADD_RULE")) { addRule() })
            add(Box.createHorizontalGlue())
        }
        val fallback = Box.createHorizontalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            txtCmd.maximumSize = Dimension(Int.MAX_VALUE, txtCmd.preferredSize.height)
            add(txtCmd)
        }
        tglRunCmd.addActionListener { txtCmd.isEnabled = tglRunCmd.isSelected }
        settingsSection(
            I8N.text("SETTINGS_SEC_COMMAND"),
            settingsFullRow(null, null, list),
            settingsFullRow(null, null, footer),
            settingsRow(I8N.text("MSG_RUN_CMD"), I8N.text("MSG_RUN_CMD_SUB"), tglRunCmd),
            settingsFullRow(null, placeholderHelp(), fallback),
        )
    }

    /** Each placeholder and what it becomes, one per line, then what happens without any. */
    private fun placeholderHelp(): String = buildString {
        append(I8N.text("CMD_PLACEHOLDERS")).append(":\n")
        for ((token, key) in PLACEHOLDER_KEYS) append(token).append("  ·  ").append(I8N.text(key)).append('\n')
        append(I8N.text("CMD_PH_NONE"))
    }

    private fun owner() = SwingUtilities.getWindowAncestor(rows)

    private fun rebuild() {
        rows.removeAll()
        rules.forEachIndexed { index, rule ->
            if (index > 0) rows.add(settingsHairline())
            rows.add(RuleRow(rule, index))
        }
        emptyHint.isVisible = rules.isEmpty()
        rows.revalidate()
        rows.repaint()
        // The card sizes itself from its rows, so the whole page has to re-lay out.
        SwingUtilities.getAncestorOfClass(SettingsPanel::class.java, rows)?.let {
            it.revalidate()
            it.repaint()
        }
    }

    private fun addRule() {
        CommandRuleDialog(owner(), null).apply { isVisible = true }.result?.let {
            rules.add(it)
            rebuild()
        }
    }

    private fun editAt(index: Int) {
        if (index !in rules.indices) return
        CommandRuleDialog(owner(), rules[index]).apply { isVisible = true }.result?.let {
            rules[index] = it
            rebuild()
        }
    }

    private fun deleteAt(index: Int) {
        if (index !in rules.indices) return
        val confirm = JOptionPane.showConfirmDialog(
            rows, I8N.text("MSG_CMD_DELETE_CONFIRM"), I8N.text("CMD_DELETE_RULE"), JOptionPane.YES_NO_OPTION
        )
        if (confirm == JOptionPane.YES_OPTION) {
            rules.removeAt(index)
            rebuild()
        }
    }

    private fun move(index: Int, by: Int) {
        val to = index + by
        if (index !in rules.indices || to !in rules.indices) return
        rules.add(to, rules.removeAt(index))
        rebuild()
    }

    fun load() {
        val config = AppContext.config
        rules.clear()
        rules.addAll(config.commandRules)
        tglRunCmd.isSelected = config.runCommand
        txtCmd.text = config.customCommand
        txtCmd.isEnabled = tglRunCmd.isSelected
        rebuild()
    }

    fun save() {
        val config = AppContext.config
        config.commandRules = rules.toList()
        config.runCommand = tglRunCmd.isSelected
        config.customCommand = txtCmd.text
    }

    /**
     * One rule: its file types in bold, its command muted underneath, then move up/down, edit, delete
     * and its on/off switch.
     */
    private inner class RuleRow(rule: CommandRule, index: Int) : JPanel(BorderLayout(12.px, 0)) {
        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = ScaledEmptyBorder(9, 0, 9, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val text = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(EllipsisLabel().apply {
                    font = font.deriveFont(Font.BOLD, 13.0f.px)
                    setText(rule.extensions.joinToString(", ") { ".$it" })
                })
                add(Box.createRigidArea(scaledSize(0, 3)))
                add(EllipsisLabel(muted = true, scale = -1f).apply {
                    setText(rule.command)
                    toolTipText = rule.command
                })
            }
            add(text, BorderLayout.CENTER)

            val toggle = SettingsToggle().apply {
                isSelected = rule.enabled
                toolTipText = I8N.text("CMD_ENABLED")
                addActionListener { rules[index] = rules[index].copy(enabled = isSelected) }
            }
            val actions = Box.createHorizontalBox().apply {
                add(settingsIconButton(RemixIcon.ARROW_UP_S_LINE, I8N.text("CMD_MOVE_UP")) { move(index, -1) }
                    .apply { isEnabled = index > 0 })
                add(settingsIconButton(RemixIcon.ARROW_DOWN_S_LINE, I8N.text("CMD_MOVE_DOWN")) { move(index, 1) }
                    .apply { isEnabled = index < rules.size - 1 })
                add(settingsIconButton(RemixIcon.EDIT_LINE, I8N.text("CMD_EDIT_RULE")) { editAt(index) })
                add(settingsIconButton(RemixIcon.DELETE_BIN_LINE, I8N.text("CMD_DELETE_RULE")) { deleteAt(index) })
                add(Box.createRigidArea(scaledSize(8, 0)))
                add(toggle)
            }
            add(actions, BorderLayout.EAST)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) editAt(index)
                }
            })
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    companion object {
        /** Each placeholder and the language key describing it. */
        val PLACEHOLDER_KEYS = listOf(
            PostDownloadCommand.FILE to "CMD_PH_FILE",
            PostDownloadCommand.NAME to "CMD_PH_NAME",
            PostDownloadCommand.BASENAME to "CMD_PH_BASENAME",
            PostDownloadCommand.EXT to "CMD_PH_EXT",
            PostDownloadCommand.FOLDER to "CMD_PH_FOLDER",
            PostDownloadCommand.FOLDER_NAME to "CMD_PH_FOLDER_NAME",
            PostDownloadCommand.TYPE to "CMD_PH_TYPE",
        )
    }
}
