package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N.text
import xdm.app.ui.screens.SettingsWindow
import xdm.app.utils.createSVGIcon
import xdm.app.utils.showMenu
import xdm.app.ui.components.SortKey
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

class AppToolBar(
    searchCallback: (String) -> Unit,
    buttonCallback: ActionListener,
    private val sortCallback: (SortKey, Boolean) -> Unit) {
    private val btnNew: JButton
    private val btnSort: JButton
    private val btnSettings: JButton
    private val btnClear: JButton
    private val btnMenu: JButton
    private val btnDelete: JButton
    private val toolbar = JToolBar()
    private val btnNewGap: Component
    private val btnSortGap: Component
    private val btnSettingsGap: Component
    private val btnClearGap: Component
    private val btnDeleteGap: Component
    private val contextMenu: JPopupMenu
    private val sortMenu: JPopupMenu
    private lateinit var mSettings: JMenuItem
    private lateinit var mBatchImport: JMenuItem
    private lateinit var mLanguage: JMenuItem
    private lateinit var mHelp: JMenuItem
    private lateinit var mUpdate: JMenuItem
    private lateinit var mAbout: JMenuItem
    private lateinit var mExit: JMenuItem
    private lateinit var mSortNameAsc: JMenuItem
    private lateinit var mSortNameDesc: JMenuItem
    private lateinit var mSortSizeAsc: JMenuItem
    private lateinit var mSortSizeDesc: JMenuItem
    private lateinit var mSortDateAsc: JMenuItem
    private lateinit var mSortDateDesc: JMenuItem


    init {
        this.contextMenu = createContextMenu()
        this.sortMenu = createSortMenu()
        this.btnNew = createToolButton("add-large-fill.svg", buttonCallback, "TOOL_DOWNLOAD")
        this.btnNewGap = Box.createRigidArea(Dimension(5, 0))

        this.btnClear = createToolButton("delete-bin-line.svg", buttonCallback, "TOOL_CLEAR")
        this.btnClearGap = Box.createRigidArea(Dimension(5, 0))

        this.btnDelete = createToolButton("delete-bin-line.svg", buttonCallback, "TOOL_DELETE")
        this.btnDeleteGap = Box.createRigidArea(Dimension(5, 0))

        this.btnSort = createToolButton("sort-desc.svg", buttonCallback, "TOOL_SORT") // "Stop all");
        this.btnSort.addActionListener {
            showMenu(btnSort, sortMenu)
        }
        this.btnSortGap = Box.createRigidArea(Dimension(5, 0))

        this.btnSettings = createToolButton("settings-4-line.svg", buttonCallback, "TOOL_SETTINGS") // "Settings");
        this.btnSettingsGap = Box.createRigidArea(Dimension(5, 0))
        this.btnSettings.addActionListener { showSettings() }

        this.btnMenu = createToolButton("menu-line.svg")
        btnMenu.addActionListener {
            showMenu(btnMenu, contextMenu)
        }

        toolbar.apply {
            add(btnNew)
            add(btnNewGap)
            add(btnClear)
            add(btnClearGap)
            add(btnSort)
            add(btnSortGap)
            add(btnSettings)
            add(btnSettingsGap)
            add(btnDelete)
            add(btnDeleteGap)
            add(Box.createHorizontalGlue())
        }

        val txtSearch = JTextField(12).apply {
            addActionListener {
                searchCallback(this.text)
            }
            putClientProperty(FlatClientProperties.STYLE, "arc: 10")
            putClientProperty("JTextField.placeholderText", "Search")
            putClientProperty(
                "JTextField.trailingIcon", createSVGIcon("search-line.svg", 16, Color.GRAY)
            )
        }
        val d = txtSearch.preferredSize
        val d2 = Dimension(d.width, d.height + 5)
        txtSearch.maximumSize = d2
        txtSearch.preferredSize = d2

        toolbar.apply {
            add(txtSearch)
            add(Box.createRigidArea(Dimension(5, 0)))
            add(btnMenu)
            add(Box.createRigidArea(Dimension(5, 0)))
        }
    }

    private fun createContextMenu(): JPopupMenu {
        val a = createMenuListener()
        val ctx = JPopupMenu()
        mSettings = addMenuItem("TITLE_SETTINGS", ctx, a)
        mBatchImport = addMenuItem("MENU_CLIP_ADD_MENU", ctx, a)
        mLanguage = addMenuItem("MENU_LANG", ctx, a)
        mUpdate = addMenuItem("MENU_UPDATE", ctx, a)
        mHelp = addMenuItem("MENU_HELP_SUP", ctx, a)
        mAbout = addMenuItem("MENU_ABOUT", ctx, a)
        mExit = addMenuItem("MENU_EXIT", ctx, a)
        return ctx
    }

    private fun createSortMenu(): JPopupMenu {
        val a = createSortMenuListener()
        val ctx = JPopupMenu()
        mSortNameAsc = addMenuItem("SORT_NAME_ASC", ctx, a)
        mSortNameDesc = addMenuItem("SORT_NAME_DESC", ctx, a)
        ctx.addSeparator()
        mSortSizeAsc = addMenuItem("SORT_SIZE_ASC", ctx, a)
        mSortSizeDesc = addMenuItem("SORT_SIZE_DESC", ctx, a)
        ctx.addSeparator()
        mSortDateAsc = addMenuItem("SORT_DATE_ASC", ctx, a)
        mSortDateDesc = addMenuItem("SORT_DATE_DESC", ctx, a)
        return ctx
    }

    private fun createSortMenuListener(): ActionListener {
        return ActionListener { e: ActionEvent ->
            val name = (e.source as? JComponent)?.name
            when (name) {
                "SORT_NAME_ASC" -> sortCallback(SortKey.NAME, true)
                "SORT_NAME_DESC" -> sortCallback(SortKey.NAME, false)
                "SORT_SIZE_ASC" -> sortCallback(SortKey.SIZE, true)
                "SORT_SIZE_DESC" -> sortCallback(SortKey.SIZE, false)
                "SORT_DATE_ASC" -> sortCallback(SortKey.DATE, true)
                "SORT_DATE_DESC" -> sortCallback(SortKey.DATE, false)
            }
        }
    }

    private fun addMenuItem(id: String, menu: JComponent, a: ActionListener): JMenuItem {
        val mItem = JMenuItem(text(id)).apply {
            name = id
            addActionListener(a)
        }
        menu.add(mItem)
        return mItem
    }

    private fun createMenuListener(): ActionListener {
        return ActionListener { e: ActionEvent ->
            val name = (e.source as? JComponent)?.name
            when (name) {
                "TITLE_SETTINGS" -> {
                    showSettings()
                }
                "MENU_CLIP_ADD_MENU" -> {

                }
                "MENU_LANG" -> {

                }
                "MENU_UPDATE" -> {

                }
                "MENU_HELP_SUP" -> {

                }
                "MENU_ABOUT" -> {

                }
                "MENU_EXIT" -> {
                    exitProcess(0)
                }
            }
        }
    }

    private fun showSettings() {
        SettingsWindow(SwingUtilities.windowForComponent(toolbar)).apply {
            isModal = true
            setLocationRelativeTo(parent)
            loadConfig()
            isVisible = true
        }
    }

    val component: Component
        get() = toolbar

    fun setMultiSelectView(isMultiSelect: Boolean) {
        btnSettings.isVisible = !isMultiSelect
        btnSettingsGap.isVisible = !isMultiSelect

        btnClear.isVisible = !isMultiSelect
        btnClearGap.isVisible = !isMultiSelect

        btnDelete.isVisible = isMultiSelect
        btnDeleteGap.isVisible = isMultiSelect
    }

    private fun createToolButton(iconName: String, callback: ActionListener, key: String): JButton {
        return JButton(text(key)).apply {
            name = key
            iconTextGap = 10
            icon = createSVGIcon(iconName, 16, Color.GRAY)
            foreground = Color.GRAY
            margin = Insets(5, 5, 5, 5)
            addActionListener(callback)
        }
    }

    private fun createToolButton(iconName: String): JButton {
        return JButton().apply {
            icon = createSVGIcon(iconName, 16, Color.GRAY)
            foreground = Color.GRAY
            margin = Insets(5, 5, 5, 5)
        }
    }
}
