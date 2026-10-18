package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.ui.screens.settings.CategoryEditDialog
import xdm.app.utils.RemixIcon
import xdm.app.utils.captureCategory
import xdm.app.utils.categoryById
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.folderFor
import xdm.app.utils.rememberCategoryPick
import xdm.app.utils.rememberHandPickedFolder
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.Window
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The Category and Save to rows of the download dialogs (docs/download-categories.md §4):
 *
 * ```
 * Category   [ Video                     ▾ ] [+]
 * Save to    [ /Users/me/Downloads/Video   ▾ ] [📁]
 * ```
 *
 * It owns the remembering rules, so every dialog behaves the same:
 * - a browser capture ([capture]) starts at the rule match unless None is remembered; anything else
 *   starts at None;
 * - hand-picking None remembers it, hand-picking a category clears it; a preselection changes nothing;
 * - a folder chosen by hand joins the history; under None it also becomes the None folder, under a
 *   category it is a one-off for this download;
 * - everything is saved the moment it changes.
 *
 * [onChanged] runs after any change of category or folder, so the dialog can refresh its storage line.
 * Call [dispose] when the dialog closes: it stops listening to config changes and closes the + child.
 */
class DestinationPicker(
    private val owner: Window,
    /** Whether the dialog is showing a browser capture, so rules apply; set before [reset]. */
    var capture: Boolean = false,
    private val onChanged: () -> Unit = {},
) {
    /** A Category combo entry; [category] null is None. */
    class Item(val category: DownloadCategory?) {
        override fun toString(): String = category?.displayName ?: text("CAT_NONE")
    }

    private val categoryModel = DefaultComboBoxModel<Item>()
    private val folderModel = DefaultComboBoxModel<String>()
    val categoryCombo = javax.swing.JComboBox(categoryModel).apply { renderer = CategoryRenderer() }
    val folderCombo = javax.swing.JComboBox(folderModel)
    val addButton = fieldIconButton(RemixIcon.ADD_FILL).apply { toolTipText = text("ND_ADD_CATEGORY") }
    val browseButton = fieldIconButton(RemixIcon.FOLDER_FILL)

    /** True while the combos are filled by code, so their listeners do not mistake it for the user. */
    private var populating = false

    /** Set once the user touched the Category combo: file-name edits stop re-matching. */
    private var userPicked = false

    /** A folder chosen by hand for this download only; null means the category's (or None's) folder. */
    private var override: String? = null

    private var fileName: String? = null
    private var url: String? = null
    private var pageUrl: String? = null
    private var addDialog: CategoryEditDialog? = null

    private val configListener: () -> Unit = { SwingUtilities.invokeLater { onConfigChanged() } }

    init {
        categoryCombo.addActionListener {
            if (populating) return@addActionListener
            userPicked = true
            override = null
            rememberCategoryPick(selectedCategory)
            refillFolders()
            onChanged()
        }
        folderCombo.addActionListener {
            if (populating) return@addActionListener
            val chosen = folderCombo.selectedItem as? String ?: return@addActionListener
            folderChosen(chosen)
        }
        browseButton.addActionListener {
            val start = File(targetFolder).let { f -> generateSequence(f) { it.parentFile }.firstOrNull { it.isDirectory } }
            chooseFile(owner, directoriesOnly = true, currentDir = start)?.let { folderChosen(it.absolutePath) }
        }
        addButton.addActionListener { openAddDialog() }
        AppContext.config.addChangeListener(configListener)
    }

    /** The category picked, or null for None. */
    val selectedCategory: DownloadCategory?
        get() = (categoryCombo.selectedItem as? Item)?.category

    /** The folder the download will be saved to. */
    val targetFolder: String
        get() = override ?: folderFor(selectedCategory)

    /**
     * Starts the rows for a new download of [fileName] from [url] (found on [pageUrl]): a browser
     * capture is preselected by the rules, anything else starts at None.
     */
    fun reset(fileName: String?, url: String?, pageUrl: String?) {
        this.fileName = fileName
        this.url = url
        this.pageUrl = pageUrl
        userPicked = false
        override = null
        refillCategories(if (capture) captureCategory(fileName, url, pageUrl) else null)
        refillFolders()
        onChanged()
    }

    /** The file name was edited: a capture re-runs the rules until the user picks a category. */
    fun fileNameChanged(name: String?) {
        fileName = name
        if (!capture || userPicked || AppContext.config.rememberNone) return
        val match = captureCategory(name, url, pageUrl)
        if (match?.id == selectedCategory?.id) return
        refillCategories(match)
        override = null
        refillFolders()
        onChanged()
    }

    fun dispose() {
        AppContext.config.removeChangeListener(configListener)
        addDialog?.dispose()
        addDialog = null
    }

    /**
     * Adds the two rows to a GridBag [panel] at [firstRow] and the next row: labels in col 0, then each
     * combo with its button right beside it, spanning cols 1-4. Both combos get the same fixed width and
     * both buttons the same square size, so the rows line up exactly without stretching the combos.
     */
    fun addTo(panel: Container, firstRow: Int, leftInset: Int = 20) {
        fun label(key: String, row: Int) = panel.add(JLabel(text(key)).apply {
            horizontalAlignment = SwingConstants.RIGHT
        }, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = scaledInsets(5, leftInset, 5, 10)
            gridx = 0
            gridy = row
        })

        fun field(combo: Component, button: Component, row: Int) = panel.add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(combo)
            add(Box.createRigidArea(scaledSize(5, 0)))
            add(button)
        }, GridBagConstraints().apply {
            gridwidth = 4
            anchor = GridBagConstraints.WEST
            insets = scaledInsets(5, 0, 5, 5)
            gridx = 1
            gridy = row
        })
        matchSizes()
        label("LBL_CATEGORY", firstRow); field(categoryCombo, addButton, firstRow)
        label("LBL_SAVE_TO", firstRow + 1); field(folderCombo, browseButton, firstRow + 1)
    }

    /** Both combos one fixed width, both icon buttons square and as tall as the combos. */
    private fun matchSizes() {
        val h = maxOf(categoryCombo.preferredSize.height, folderCombo.preferredSize.height)
        listOf(categoryCombo, folderCombo).forEach {
            val d = Dimension(COMBO_WIDTH.px, h)
            it.preferredSize = d
            it.minimumSize = d
            it.maximumSize = d
        }
        listOf(addButton, browseButton).forEach {
            it.preferredSize = Dimension(h, h)
            it.minimumSize = Dimension(h, h)
            it.maximumSize = Dimension(h, h)
        }
    }

    private fun folderChosen(chosen: String) {
        val path = File(chosen).absolutePath
        val resolved = File(folderFor(selectedCategory)).absolutePath
        override = if (path == resolved) null else path
        rememberHandPickedFolder(path, underNone = selectedCategory == null)
        refillFolders()
        onChanged()
    }

    private fun openAddDialog() {
        addDialog?.let { it.toFront(); it.requestFocus(); return }
        addDialog = CategoryEditDialog(owner, null, modal = false, onSaved = { added ->
            AppContext.config.categories = AppContext.config.categories + added
            AppContext.config.save()
            // Selected as a hand-pick: it turns rule preselection back on, like picking it from the list.
            userPicked = true
            override = null
            rememberCategoryPick(added)
            refillCategories(added)
            refillFolders()
            onChanged()
        }).apply {
            addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) {
                    addDialog = null
                }
            })
            isVisible = true
        }
    }

    /** Categories or the default folder changed elsewhere (Settings, another dialog's +). */
    private fun onConfigChanged() {
        if (!categoryCombo.isDisplayable) return
        // A category deleted while selected falls back to None, without counting as a pick.
        val keep = selectedCategory?.id?.let { categoryById(it) }
        refillCategories(keep)
        refillFolders()
        onChanged()
    }

    private fun refillCategories(select: DownloadCategory?) {
        populating = true
        try {
            categoryModel.removeAllElements()
            AppContext.config.categories.forEach { categoryModel.addElement(Item(it)) }
            categoryModel.addElement(Item(null))
            val index = select?.let { s -> (0 until categoryModel.size).firstOrNull { categoryModel.getElementAt(it).category?.id == s.id } }
            categoryCombo.selectedIndex = index ?: (categoryModel.size - 1)
        } finally {
            populating = false
        }
    }

    /** The resolved folder first and selected (or the one-off folder), then the history, then the default. */
    private fun refillFolders() {
        populating = true
        try {
            val resolved = folderFor(selectedCategory)
            val items = (listOfNotNull(override, resolved) + AppContext.config.folderHistory +
                    AppContext.config.defaultDownloadFolder)
                .map { File(it).absolutePath }
                .distinct()
            folderModel.removeAllElements()
            items.forEach { folderModel.addElement(it) }
            folderCombo.selectedItem = File(override ?: resolved).absolutePath
            folderCombo.toolTipText = folderCombo.selectedItem as? String
        } finally {
            populating = false
        }
    }

    /** Shows each category with its color dot, as the sidebar does; None with the neutral one. */
    private class CategoryRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (c is JLabel) {
                c.icon = CategoryDotIcon(AppTheme.tileHue((value as? Item)?.category))
                c.iconTextGap = 4
            }
            return c
        }
    }

    companion object {
        /** Width of the Category and Save to combos at 100%; long paths are cut, the tooltip has them. */
        private const val COMBO_WIDTH = 320

        /** The square gray icon button beside a field (Browse, +), identical for every such button. */
        fun fieldIconButton(icon: RemixIcon): JButton = JButton(createIcon(icon, 16, Color.GRAY))
    }
}
