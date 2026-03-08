package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.utils.AppUtils
import xdman.ui.res.StringResource
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JTextField
import javax.swing.JToolBar

class AppToolBar(searchCallback: (String) -> Unit, buttonCallback: ActionListener) {
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

    init {
        this.btnNew = createToolButton("add-large-fill.svg", buttonCallback, "TOOL_DOWNLOAD")
        this.btnNewGap = Box.createRigidArea(Dimension(5, 0))

        this.btnClear = createToolButton("delete-bin-line.svg", buttonCallback, "TOOL_CLEAR")
        this.btnClearGap = Box.createRigidArea(Dimension(5, 0))

        this.btnDelete = createToolButton("delete-bin-line.svg", buttonCallback, "TOOL_DELETE")
        this.btnDeleteGap = Box.createRigidArea(Dimension(5, 0))

        this.btnSort = createToolButton("sort-desc.svg", buttonCallback, "TOOL_SORT") // "Stop all");
        this.btnSortGap = Box.createRigidArea(Dimension(5, 0))

        this.btnSettings = createToolButton("settings-4-line.svg", buttonCallback, "TOOL_SETTINGS") // "Settings");
        this.btnSettingsGap = Box.createRigidArea(Dimension(5, 0))

        this.btnMenu = createToolButton("menu-line.svg")
        btnMenu.addActionListener { e: ActionEvent? -> System.exit(0) }

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
                "JTextField.trailingIcon", AppUtils.createSVGIcon("search-line.svg", 16, Color.GRAY)
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
        return JButton(StringResource.get(key)).apply {
            name = key
            iconTextGap = 10
            icon = AppUtils.createSVGIcon(iconName, 16, Color.GRAY)
            foreground = Color.GRAY
            margin = Insets(5, 5, 5, 5)
            addActionListener(callback)
        }
    }

    private fun createToolButton(iconName: String): JButton {
        return JButton().apply {
            icon = AppUtils.createSVGIcon(iconName, 16, Color.GRAY)
            foreground = Color.GRAY
            margin = Insets(5, 5, 5, 5)
        }
    }
}
