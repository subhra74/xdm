package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N.text
import xdm.app.ui.screens.settings.AdvancedConfigPanel
import xdm.app.ui.screens.settings.BrowserMonitorPanel
import xdm.app.ui.screens.settings.GeneralPanel
import xdm.app.ui.screens.settings.NetworkConfigPanel
import xdm.app.utils.padding
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder


class SettingsWindow(parent: Window) : JDialog(parent) {
    private val generalPanel = GeneralPanel()
    private val browserMonitorPanel = BrowserMonitorPanel()
    private val browserMonitorPanelJsp = JScrollPane(browserMonitorPanel).apply { border = EmptyBorder(0, 0, 0, 0) }
    private val networkConfigPanel = NetworkConfigPanel()
    private val advancedConfigPanel = AdvancedConfigPanel()
    private val card = CardLayout()
    private val panelHolder = JPanel(card)
    private val panelCenter = JPanel(BorderLayout())
    private val panelIndices = arrayOf("GEN_PAN", "BRM_PAN", "NET_PAN", "ADV_PAN")
    private val btnSave = JButton(text("DESC_SAVE_Q")).apply {
        addActionListener {
            save()
            dispose()
        }
    }
    private val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }
    private val leftList =
        JList(
            arrayOf(
                text("SETTINGS_GENERAL"),
                text("SETTINGS_MONITORING"),
                text("SETTINGS_NETWORK"),
                text("MSG_ADV_TITLE")
            )
        ).apply {
            putClientProperty(FlatClientProperties.STYLE, "cellMargins: 10,15,10,25")
            addListSelectionListener { card.show(panelHolder, panelIndices[selectedIndex]) }
        }

    init {
        size = Dimension(550, 450)
        title = text("TITLE_SETTINGS")

        val boxBottom = Box.createHorizontalBox().apply {
            add(Box.createHorizontalGlue())
            add(btnSave)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(btnCancel)
            padding(this, 10, topPadding = true)
            add(Box.createRigidArea(Dimension(10, 10)))
        }

        panelCenter.add(panelHolder)
        panelCenter.add(boxBottom, BorderLayout.SOUTH)

        add(leftList, BorderLayout.WEST)
        add(panelCenter, BorderLayout.CENTER)

        panelHolder.add(generalPanel, "GEN_PAN")
        panelHolder.add(browserMonitorPanelJsp, "BRM_PAN")
        panelHolder.add(networkConfigPanel, "NET_PAN")
        panelHolder.add(advancedConfigPanel, "ADV_PAN")
        leftList.selectedIndex = 0
    }

    fun save() {
        generalPanel.save()
        browserMonitorPanel.save()
    }

    fun loadConfig() {
        generalPanel.load()
        browserMonitorPanel.load()
    }
}
