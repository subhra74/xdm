package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N
import xdm.app.ui.components.CategoryStyle
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.fixHeight
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * Settings page for user-defined download categories. Edits a working copy; the list is
 * only pushed into the config by [save], so cancelling the settings window discards it.
 */
class CategoryPanel : SettingsPanel() {
    private val model = DefaultListModel<DownloadCategory>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = CategoryRenderer()
        visibleRowCount = 7
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && selectedIndex >= 0) editSelected()
            }
        })
    }

    private val btnAdd = actionButton("CAT_ADD", RemixIcon.ADD_LARGE_FILL) { addCategory() }
    private val btnEdit = actionButton("CAT_EDIT", RemixIcon.EDIT_LINE) { editSelected() }
    private val btnDelete = actionButton("CAT_DELETE", RemixIcon.DELETE_BIN_LINE) { deleteSelected() }
    private val btnDefaults = JButton(I8N.text("DESC_DEF")).apply {
        fixHeight(this)
        addActionListener { restoreDefaults() }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("CAT_TEXT")))

        val buttons = Box.createHorizontalBox().apply {
            add(btnAdd)
            add(Box.createRigidArea(Dimension(8, 0)))
            add(btnEdit)
            add(Box.createRigidArea(Dimension(8, 0)))
            add(btnDelete)
            add(Box.createHorizontalGlue())
            add(btnDefaults)
        }

        val scroll = JScrollPane(list).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(0, 230)
            maximumSize = Dimension(Int.MAX_VALUE, 230)
        }

        add(
            settingsCard(
                I8N.text("CAT_SEC_CATEGORIES"),
                caption(I8N.text("CAT_DESC")),
                scroll,
                buttons,
            )
        )
        add(Box.createVerticalGlue())
    }

    private fun actionButton(key: String, icon: RemixIcon, action: () -> Unit) =
        JButton(I8N.text(key), createIcon(icon, 16, settingsAccentColor())).apply {
            iconTextGap = 6
            fixHeight(this)
            addActionListener { action() }
        }

    private fun owner() = SwingUtilities.getWindowAncestor(this)

    private fun addCategory() {
        val dlg = CategoryEditDialog(owner(), null).apply { isVisible = true }
        dlg.result?.let { model.addElement(it) }
    }

    private fun editSelected() {
        val index = list.selectedIndex
        if (index < 0) return
        val dlg = CategoryEditDialog(owner(), model[index]).apply { isVisible = true }
        dlg.result?.let { model[index] = it }
    }

    private fun deleteSelected() {
        val index = list.selectedIndex
        if (index < 0) return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            I8N.text("MSG_CAT_DELETE_CONFIRM"),
            I8N.text("CAT_DELETE"),
            JOptionPane.YES_NO_OPTION
        )
        if (confirm == JOptionPane.YES_OPTION) {
            model.remove(index)
            list.selectedIndex = (index - 1).coerceAtLeast(0)
        }
    }

    /** Re-adds any missing built-in, leaving the user's own categories alone. */
    private fun restoreDefaults() {
        val present = (0 until model.size()).map { model[it].id }.toSet()
        AppContext.config.defaultCategories
            .filterNot { present.contains(it.id) }
            .forEach { model.addElement(it) }
    }

    fun load() {
        model.clear()
        AppContext.config.categories.forEach { model.addElement(it) }
        if (!model.isEmpty) list.selectedIndex = 0
    }

    fun save() {
        AppContext.config.categories = (0 until model.size()).map { model[it] }
    }

    override fun getInsets(): Insets = Insets(10, 12, 12, 12)

    /**
     * One row: the category's glyph, its name and file types, then the folder its
     * downloads land in — derived from the default download folder when the user set none,
     * so every row shows a real path rather than a blank.
     */
    private class CategoryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is DownloadCategory && c is JLabel) {
                c.icon = createIcon(CategoryStyle.iconName(value), 16, settingsAccentColor())
                c.iconTextGap = 10
                c.verticalTextPosition = SwingConstants.TOP
                c.border = EmptyBorder(4, 4, 4, 4)
                val folder = value.folderFor(AppContext.config.defaultDownloadFolder)
                // The muted gray is unreadable on the selection fill, so the selected row
                // keeps the full-strength foreground for both lines.
                val subColor = if (isSelected) c.foreground else settingsMutedColor()
                c.text = "<html><b>${escape(value.displayName)}</b> &nbsp;${
                    escape(DownloadCategory.formatExtensions(value.extensions))
                }<br><font color='${hex(subColor)}'>${escape(folder)}</font></html>"
            }
            return c
        }

        private fun escape(s: String) =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun hex(c: Color) = String.format("#%06x", c.rgb and 0xFFFFFF)
    }
}

/** Small muted caption line, matching the other settings panels. */
private fun caption(text: String): JLabel = JLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    foreground = settingsMutedColor()
    font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
}
