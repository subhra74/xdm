package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/** What the browser extension hands over: which file types are captured, and from which sites. */
class BrowserMonitorPanel : SettingsPanel() {
    private val txtFileExt = extensionArea()
    private val txtVidExt = extensionArea()
    private val txtBlockedHosts = extensionArea()
    private val cmbMinVidSize = rounded(JComboBox<Long>()).apply {
        listOf(1L, 5L, 10L, 50L, 100L).forEach { addItem(it) }
        preferredSize = Dimension(110, preferredSize.height)
        maximumSize = preferredSize
    }
    private val tglGetServerTime = SettingsToggle()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_MONITORING")))

        val browsers = JPanel(GridLayout(1, 4, 10, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(BrowserTile(RemixIcon.CHROME_FILL, "Chrome", Color(0x4285F4)))
            add(BrowserTile(RemixIcon.FIREFOX_FILL, "Firefox", Color(0xFF7139)))
            add(BrowserTile(RemixIcon.EDGE_NEW_FILL, "Edge", Color(0x24B0C4)))
            add(BrowserTile(RemixIcon.GLOBAL_FILL, I8N.text("SETTINGS_BROWSER_OTHER"), settingsAccentColor()))
        }
        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_BROWSERS"),
                settingsFullRow(null, I8N.text("SETTINGS_SEC_BROWSERS_SUB"), browsers),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_FILETYPES"),
                settingsFullRow(
                    I8N.text("DESC_FILETYPES"),
                    I8N.text("DESC_FILETYPES_SUB"),
                    areaWithReset(txtFileExt) { AppContext.config.defFileExtensions }
                ),
                settingsFullRow(
                    I8N.text("DESC_VIDEOTYPES"),
                    I8N.text("DESC_VIDEOTYPES_SUB"),
                    areaWithReset(txtVidExt) { AppContext.config.defVideoExtensions }
                ),
                settingsRow(
                    I8N.text("LBL_MIN_VIDEO_SIZE"),
                    I8N.text("LBL_MIN_VIDEO_SIZE_SUB"),
                    Box.createHorizontalBox().apply {
                        add(cmbMinVidSize)
                        add(Box.createRigidArea(Dimension(8, 0)))
                        add(JLabel("MB").apply { foreground = settingsMutedColor() })
                    }
                ),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_EXCEPTIONS"),
                settingsFullRow(
                    I8N.text("DESC_SITEEXCEPTIONS"),
                    I8N.text("DESC_SITEEXCEPTIONS_SUB"),
                    areaWithReset(txtBlockedHosts) { AppContext.config.defBlockedHosts }
                ),
                settingsRow(I8N.text("LBL_GET_TIMESTAMP"), I8N.text("LBL_GET_TIMESTAMP_SUB"), tglGetServerTime),
            )
        )

        add(Box.createVerticalGlue())
    }

    private fun extensionArea(): JTextArea = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
        border = EmptyBorder(7, 9, 7, 9)
    }

    /**
     * A rounded, wrapping list of comma-separated values with its reset button underneath.
     * `lineWrap` keeps a long list inside the pane's width, so it only ever scrolls vertically.
     */
    private fun areaWithReset(area: JTextArea, defaults: () -> List<String>): JComponent {
        val scroll = rounded(JScrollPane(area)).apply {
            alignmentX = LEFT_ALIGNMENT
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(preferredSize.width, 78)
            maximumSize = Dimension(Int.MAX_VALUE, 78)
        }
        return Box.createVerticalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(scroll)
            add(Box.createRigidArea(Dimension(0, 9)))
            add(settingsLeftAligned(settingsButton(I8N.text("DESC_DEF")) {
                area.text = defaults().joinToString(", ")
            }))
        }
    }

    fun load() {
        val config = AppContext.config
        txtFileExt.text = config.fileExtensions.joinToString(", ")
        txtVidExt.text = config.videoExtensions.joinToString(", ")
        txtBlockedHosts.text = config.blockedHosts.joinToString(", ")
        cmbMinVidSize.selectedItem = config.minVideoSize
        tglGetServerTime.isSelected = config.getServerTime
    }

    fun save() {
        val config = AppContext.config
        config.fileExtensions = txtFileExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.videoExtensions = txtVidExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.blockedHosts = txtBlockedHosts.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.minVideoSize = cmbMinVidSize.selectedItem as Long
        config.getServerTime = tglGetServerTime.isSelected
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)

    /** A rounded browser "tile": brand-tinted icon over a name, with a hover highlight. */
    private class BrowserTile(iconName: RemixIcon, label: String, private val accent: Color) : JPanel() {
        private var hovered = false

        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(16, 8, 14, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(JLabel(createIcon(iconName, 40, accent)).apply {
                alignmentX = CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
            })
            add(JLabel(label).apply {
                alignmentX = CENTER_ALIGNMENT
                font = font.deriveFont(Font.BOLD)
                border = EmptyBorder(10, 0, 0, 0)
            })

            val hoverListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    // Ignore transitions onto our own child components.
                    val pt = SwingUtilities.convertPoint(e.component, e.point, this@BrowserTile)
                    if (!contains(pt)) {
                        hovered = false
                        repaint()
                    }
                }
            }
            addMouseListener(hoverListener)
            components.forEach { it.addMouseListener(hoverListener) }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = settingsSurface(hovered)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            if (hovered) {
                g2.color = Color(accent.red, accent.green, accent.blue, 170)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
