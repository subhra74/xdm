package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N
import xdm.app.ui.components.CategoryStyle
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
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
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * The "File categories" card. Edits a working copy of the category list; [save] is what
 * pushes it into the config, so cancelling the settings window discards the edits.
 *
 * Categories are drawn as a plain vertical stack rather than a fixed-height `JList`. The list
 * used to sit in its own 170px `JScrollPane` *inside* the page's scroll pane — two nested
 * vertical scrollers — and its renderer put the whole extension list into the cell, whose
 * preferred width then forced a horizontal scroll bar as well. Here the stack grows and the
 * page scrolls, and every user-supplied string goes through an [EllipsisLabel], which reports
 * a preferred width of zero. Neither scroll bar can appear.
 */
class CategorySection {
    private val categories = mutableListOf<DownloadCategory>()
    private val rows = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    private val emptyHint = settingsHint(I8N.text("CAT_EMPTY")).apply { isVisible = false }

    /** The card to drop into a settings panel. */
    val component: JComponent by lazy {
        val footer = Box.createHorizontalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsButton(I8N.text("CAT_ADD")) { addCategory() })
            add(Box.createHorizontalGlue())
            add(settingsButton(I8N.text("DESC_DEF")) { restoreDefaults() })
        }
        val list = Box.createVerticalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(settingsLeftAligned(settingsHint(I8N.text("CAT_DESC"))))
            add(Box.createRigidArea(Dimension(0, 4)))
            add(rows)
            add(settingsLeftAligned(emptyHint))
        }
        settingsSection(
            I8N.text("CAT_SEC_CATEGORIES"),
            settingsFullRow(null, null, list),
            settingsFullRow(null, null, footer),
        )
    }

    private fun owner() = SwingUtilities.getWindowAncestor(rows)

    private fun rebuild() {
        rows.removeAll()
        categories.forEachIndexed { index, category ->
            if (index > 0) rows.add(settingsHairline())
            rows.add(CategoryRow(category, index))
        }
        emptyHint.isVisible = categories.isEmpty()
        rows.revalidate()
        rows.repaint()
        // The card sizes itself from its rows, so the whole page has to re-lay out.
        SwingUtilities.getAncestorOfClass(SettingsPanel::class.java, rows)?.let {
            it.revalidate()
            it.repaint()
        }
    }

    private fun addCategory() {
        val dlg = CategoryEditDialog(owner(), null).apply { isVisible = true }
        dlg.result?.let {
            categories.add(it)
            rebuild()
        }
    }

    private fun editAt(index: Int) {
        if (index !in categories.indices) return
        val dlg = CategoryEditDialog(owner(), categories[index]).apply { isVisible = true }
        dlg.result?.let {
            categories[index] = it
            rebuild()
        }
    }

    private fun deleteAt(index: Int) {
        if (index !in categories.indices) return
        val confirm = JOptionPane.showConfirmDialog(
            rows,
            I8N.text("MSG_CAT_DELETE_CONFIRM"),
            I8N.text("CAT_DELETE"),
            JOptionPane.YES_NO_OPTION
        )
        if (confirm == JOptionPane.YES_OPTION) {
            categories.removeAt(index)
            rebuild()
        }
    }

    /** Re-adds any missing built-in, leaving the user's own categories alone. */
    private fun restoreDefaults() {
        val present = categories.map { it.id }.toSet()
        AppContext.config.defaultCategories
            .filterNot { present.contains(it.id) }
            .forEach { categories.add(it) }
        rebuild()
    }

    fun load() {
        categories.clear()
        categories.addAll(AppContext.config.categories)
        rebuild()
    }

    fun save() {
        AppContext.config.categories = categories.toList()
    }

    /**
     * One category: its glyph, its name, and a muted second line with the file-type count and
     * the folder the downloads land in. The full extension list is the tooltip — spelling it
     * out inline is what used to make the row wider than the page.
     */
    private inner class CategoryRow(category: DownloadCategory, index: Int) : JPanel(BorderLayout(12, 0)) {
        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(9, 0, 9, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(
                JLabel(createIcon(CategoryStyle.iconName(category), 20, settingsAccentColor())).apply {
                    verticalAlignment = SwingConstants.TOP
                    border = EmptyBorder(2, 2, 0, 0)
                },
                BorderLayout.WEST
            )

            val extensions = DownloadCategory.formatExtensions(category.extensions)
            val text = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(EllipsisLabel().apply {
                    font = font.deriveFont(Font.BOLD, 13.0f)
                    setText(category.displayName)
                })
                add(Box.createRigidArea(Dimension(0, 3)))
                add(EllipsisLabel(muted = true, scale = -1f).apply {
                    setText(
                        I8N.text("CAT_TYPE_COUNT").format(category.extensions.size) + "  ·  " + category.folder
                    )
                    toolTipText = extensions
                })
            }
            add(text, BorderLayout.CENTER)

            val actions = Box.createHorizontalBox().apply {
                add(settingsIconButton(RemixIcon.EDIT_LINE, I8N.text("CAT_EDIT")) { editAt(index) })
                add(settingsIconButton(RemixIcon.DELETE_BIN_LINE, I8N.text("CAT_DELETE")) { deleteAt(index) })
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
}
