package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.screens.settings.AdvancedConfigPanel
import xdm.app.ui.screens.settings.BrowserMonitorPanel
import xdm.app.ui.screens.settings.DownloadsPanel
import xdm.app.ui.screens.settings.FoldersPanel
import xdm.app.ui.screens.settings.GeneralPanel
import xdm.app.ui.screens.settings.NetworkConfigPanel
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.ui.screens.settings.settingsMutedColor
import xdm.app.ui.screens.settings.settingsStroke
import xdm.app.ui.screens.settings.settingsSurface
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Box
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Six pages, one question each: what XDM does, how fast it downloads, where files land, what
 * the browser hands over, how it reaches the network, and the rest. The old four-page split
 * put folders, categories, appearance and behaviour on one page that ran to roughly twice the
 * dialog's height.
 */
class SettingsWindow(parent: Window) : JDialog(parent) {
    private class Page(val key: String, val icon: RemixIcon, val panel: JComponent)

    private val generalPanel = GeneralPanel()
    private val downloadsPanel = DownloadsPanel()
    private val foldersPanel = FoldersPanel()
    private val browserMonitorPanel = BrowserMonitorPanel()
    private val networkConfigPanel = NetworkConfigPanel()
    private val advancedConfigPanel = AdvancedConfigPanel()

    private val pages = listOf(
        Page("SETTINGS_GENERAL", RemixIcon.SETTINGS_4_LINE, generalPanel),
        Page("SETTINGS_DOWNLOADS", RemixIcon.DOWNLOAD_2_LINE, downloadsPanel),
        Page("SETTINGS_FOLDERS", RemixIcon.FOLDER_3_LINE, foldersPanel),
        Page("SETTINGS_MONITORING", RemixIcon.PUZZLE_LINE, browserMonitorPanel),
        Page("SETTINGS_NETWORK", RemixIcon.WIFI_LINE, networkConfigPanel),
        Page("MSG_ADV_TITLE", RemixIcon.TERMINAL_BOX_LINE, advancedConfigPanel),
    )

    /** Called after a successful save so the main window can pick up edited categories. */
    var onSaved: (() -> Unit)? = null

    private val card = java.awt.CardLayout()
    private val panelHolder = JPanel(card)

    private val btnSave = xdm.app.ui.screens.settings.settingsButton(text("DESC_SAVE_Q")) {
        save()
        dispose()
    }
    private val btnCancel = xdm.app.ui.screens.settings.settingsButton(text("ND_CANCEL")) { dispose() }

    private val navList = JList(pages.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        isOpaque = false
        fixedCellHeight = 38
        border = EmptyBorder(10, 8, 10, 8)
        cellRenderer = NavRenderer()
        addListSelectionListener { if (selectedIndex >= 0) card.show(panelHolder, pages[selectedIndex].key) }
    }

    init {
        // Matches the main window's initial size, so Settings opens at the size the app did.
        size = Dimension(800, 500)
        minimumSize = Dimension(720, 460)
        title = text("TITLE_SETTINGS")
        rootPane.defaultButton = btnSave

        pages.forEach { page ->
            panelHolder.add(
                JScrollPane(page.panel).apply {
                    border = EmptyBorder(0, 0, 0, 0)
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBar.unitIncrement = 18
                },
                page.key
            )
        }

        val bottom = Box.createHorizontalBox().apply {
            border = EmptyBorder(14, 24, 16, 24)
            add(Box.createHorizontalGlue())
            add(btnCancel)
            add(Box.createRigidArea(Dimension(10, 0)))
            add(btnSave)
        }

        val center = JPanel(BorderLayout()).apply {
            add(panelHolder, BorderLayout.CENTER)
            add(FooterBar(bottom), BorderLayout.SOUTH)
        }

        add(NavRail(navList), BorderLayout.WEST)
        add(center, BorderLayout.CENTER)

        navList.selectedIndex = 0
        btnSave.requestFocusInWindow()

        addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent) {
                Logger.info("Settings window activated")
                btnSave.requestFocusInWindow()
            }
        })
    }

    fun save() {
        generalPanel.save()
        downloadsPanel.save()
        foldersPanel.save()
        browserMonitorPanel.save()
        networkConfigPanel.save()
        advancedConfigPanel.save()
        AppContext.config.save()
        onSaved?.invoke()
    }

    fun loadConfig() {
        generalPanel.load()
        downloadsPanel.load()
        foldersPanel.load()
        browserMonitorPanel.load()
        networkConfigPanel.load()
        advancedConfigPanel.load()
    }

    /** The left rail: its own tinted surface, separated from the content by a hairline. */
    private class NavRail(list: JList<*>) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            preferredSize = Dimension(190, 0)
            add(list, BorderLayout.NORTH)
        }

        override fun paintComponent(g: Graphics) {
            g.color = settingsSurface()
            g.fillRect(0, 0, width, height)
            g.color = settingsStroke()
            g.fillRect(width - 1, 0, 1, height)
            super.paintComponent(g)
        }
    }

    /** Separates the Save/Cancel row from the scrolling page above it. */
    private class FooterBar(content: JComponent) : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            add(content, BorderLayout.CENTER)
        }

        override fun paintComponent(g: Graphics) {
            g.color = settingsStroke()
            g.fillRect(0, 0, width, 1)
            super.paintComponent(g)
        }
    }

    /** A nav entry: glyph, label, and a rounded accent pill behind the selected one. */
    private class NavRenderer : DefaultListCellRenderer() {
        private var selected = false

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            selected = isSelected
            isOpaque = false
            border = EmptyBorder(0, 12, 0, 12)
            iconTextGap = 11
            font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13.0f)
            if (value is Page) {
                val tint = if (isSelected) settingsAccentColor() else settingsMutedColor()
                icon = createIcon(value.icon, 18, tint)
                text = xdm.app.I8N.text(value.key)
            }
            foreground = if (isSelected) UIManager.getColor("Label.foreground") else settingsMutedColor()
            return this
        }

        override fun paintComponent(g: Graphics) {
            if (selected) {
                val accent = settingsAccentColor()
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(accent.red, accent.green, accent.blue, 46)
                g2.fillRoundRect(0, 2, width, height - 4, 12, 12)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
