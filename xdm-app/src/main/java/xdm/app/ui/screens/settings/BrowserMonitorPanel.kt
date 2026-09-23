package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.fixHeight
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
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

class BrowserMonitorPanel : SettingsPanel() {
    private val txtFileExt = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val txtVidExt = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val btnExtDef = JButton(I8N.text("DESC_DEF")).apply {
        addActionListener { txtFileExt.text = AppContext.config.defFileExtensions.joinToString(", ") }
    }
    private val btnVidExtDef = JButton(I8N.text("DESC_DEF")).apply {
        addActionListener { txtVidExt.text = AppContext.config.defVideoExtensions.joinToString(", ") }
    }
    private val cmbMinVidSize = JComboBox<Long>().apply {
        fixHeight(this)
        preferredSize = Dimension(150, preferredSize.height)
        maximumSize = Dimension(150, preferredSize.height)
        addItem(1L)
        addItem(5L)
        addItem(10L)
        addItem(50L)
        addItem(100L)
    }
    private val txtBlockedHosts = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val btnHostDef = JButton(I8N.text("DESC_DEF")).apply {
        addActionListener { txtBlockedHosts.text = AppContext.config.defBlockedHosts.joinToString(", ") }
    }
    private val chkGetServerTime = JCheckBox(I8N.text("LBL_GET_TIMESTAMP"))

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        add(settingsTitle(I8N.text("BROWSER_MONITORING")))

        // Supported browsers
        val browsers = JPanel(GridLayout(1, 4, 12, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(BrowserTile(RemixIcon.CHROME_FILL, "Chrome", Color(0x4285F4)))
            add(BrowserTile(RemixIcon.FIREFOX_FILL, "Firefox", Color(0xFF7139)))
            add(BrowserTile(RemixIcon.EDGE_NEW_FILL, "Edge", Color(0x24B0C4)))
            add(BrowserTile(RemixIcon.GLOBAL_FILL, "Other", settingsAccentColor()))
        }
        add(settingsCard(I8N.text("SETTINGS_SEC_BROWSERS"), browsers))
        add(Box.createRigidArea(Dimension(0, 12)))

        // File types
        val minSizeRow = Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(JLabel(I8N.text("LBL_MIN_VIDEO_SIZE")))
            add(Box.createHorizontalGlue())
            add(cmbMinVidSize)
            add(Box.createRigidArea(Dimension(6, 0)))
            add(JLabel("MB"))
        }
        add(
            settingsCard(
                I8N.text("SETTINGS_SEC_FILETYPES"),
                caption(I8N.text("DESC_FILETYPES")),
                textAreaScroll(txtFileExt),
                settingsLeftAligned(btnExtDef),
                caption(I8N.text("DESC_VIDEOTYPES")),
                textAreaScroll(txtVidExt),
                settingsLeftAligned(btnVidExtDef),
                minSizeRow,
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Site exceptions
        add(
            settingsCard(
                I8N.text("SETTINGS_SEC_EXCEPTIONS"),
                caption(I8N.text("DESC_SITEEXCEPTIONS")),
                textAreaScroll(txtBlockedHosts),
                settingsLeftAligned(btnHostDef),
                settingsLeftAligned(chkGetServerTime),
            )
        )

        add(Box.createVerticalGlue())
    }

    private fun caption(text: String): JLabel = JLabel(text).apply { foreground = settingsMutedColor() }

    private fun textAreaScroll(area: JTextArea): JScrollPane =
        JScrollPane(area).apply {
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(preferredSize.width, 74)
            maximumSize = Dimension(Int.MAX_VALUE, 74)
        }

    fun load() {
        val config = AppContext.config
        txtFileExt.text = config.fileExtensions.joinToString(", ")
        txtVidExt.text = config.videoExtensions.joinToString(", ")
        txtBlockedHosts.text = config.blockedHosts.joinToString(", ")
        cmbMinVidSize.selectedItem = config.minVideoSize
        chkGetServerTime.isSelected = config.getServerTime
    }

    fun save() {
        val config = AppContext.config
        config.fileExtensions = txtFileExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.videoExtensions = txtVidExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.blockedHosts = txtBlockedHosts.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.minVideoSize = cmbMinVidSize.selectedItem as Long
        config.getServerTime = chkGetServerTime.isSelected
    }

    override fun getInsets(): Insets {
        return Insets(10, 12, 12, 12)
    }

    /** A rounded browser "tile": brand-tinted icon over a name, with a hover highlight. */
    private class BrowserTile(iconName: RemixIcon, label: String, private val accent: Color) : JPanel() {
        private var hovered = false

        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(16, 8, 14, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(JLabel(createIcon(iconName, 44, accent)).apply {
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
            val base = UIManager.getColor("Panel.background") ?: Color(0x3C, 0x3F, 0x41)
            val luminance = (base.red * 299 + base.green * 587 + base.blue * 114) / 1000
            val fill = if (luminance < 128) shift(base, if (hovered) 34 else 26) else shift(base, if (hovered) -16 else -6)

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
            if (hovered) {
                g2.color = Color(accent.red, accent.green, accent.blue, 170)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
            }
            g2.dispose()
            super.paintComponent(g)
        }

        private fun shift(c: Color, amount: Int): Color {
            fun clamp(v: Int) = v.coerceIn(0, 255)
            return Color(clamp(c.red + amount), clamp(c.green + amount), clamp(c.blue + amount))
        }
    }
}
