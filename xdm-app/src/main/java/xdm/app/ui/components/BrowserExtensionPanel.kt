package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.ui.screens.settings.settingsIconButton
import xdm.app.ui.screens.settings.settingsMutedColor
import xdm.app.ui.screens.settings.rounded
import xdm.app.ui.screens.settings.settingsStroke
import xdm.app.utils.BrandFontIcon
import xdm.app.utils.BrandIcon
import xdm.app.utils.Browser
import xdm.app.utils.BrowserLauncher
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.WEBSITE_URL
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.scaledSize
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread

/**
 * The "install the browser extension" block, shared by the settings Browsers section and the
 * first-run [xdm.app.ui.screens.BrowserSetupDialog]: a row of browser tiles, each opening XDM's install
 * page for that browser, then a picker for other browsers with a copyable link to open in them. The app
 * never links a store directly: the website pages carry the store links, so a listing can move without
 * an app update, and they walk the user through the steps after installing.
 */
class BrowserExtensionPanel : JPanel() {

    private val hint = JLabel().apply {
        alignmentX = LEFT_ALIGNMENT
        foreground = settingsMutedColor()
        border = ScaledEmptyBorder(0, 2, 7, 0)
    }
    private val link = LinkRow(fitText = false)

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(JPanel(GridLayout(1, 0, 8.px, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(BrowserTile(Browser.CHROME, BrandIcon.CHROME, Color(0x4285F4), extensionPageUrl("chrome")))
            add(BrowserTile(Browser.FIREFOX, BrandIcon.FIREFOX_BROWSER, Color(0xFF7139), extensionPageUrl("firefox")))
            add(BrowserTile(Browser.EDGE, BrandIcon.EDGE, Color(0x24B0C4), extensionPageUrl("edge")))
            add(BrowserTile(Browser.BRAVE, BrandIcon.BRAVE, Color(0xFB542B), extensionPageUrl("brave")))
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })

        val picker = rounded(JComboBox(OTHER_BROWSERS.toTypedArray())).apply {
            maximumSize = preferredSize
            addActionListener { (selectedItem as? OtherBrowser)?.let { show(it) } }
        }
        add(Box.createRigidArea(scaledSize(0, 16)))
        add(Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(JLabel(I8N.text("BROWSER_EXT_OTHER")).apply { border = ScaledEmptyBorder(0, 2, 0, 0) })
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(Box.createHorizontalGlue())
            add(picker)
        })
        add(Box.createRigidArea(scaledSize(0, 12)))
        add(hint)
        add(link)
        show(OTHER_BROWSERS.first())
    }

    /** Points the hint and the link at [browser]'s family install page. */
    private fun show(browser: OtherBrowser) {
        hint.text = String.format(I8N.text("BROWSER_EXT_OPEN_IN"), browser.toString())
        link.url = extensionPageUrl(if (browser.firefoxBased) "firefox" else "chromium")
    }

    /**
     * Another browser the extension works in, by the extension it takes. With [name] null it is the
     * catch-all entry for the rest of that family.
     */
    private class OtherBrowser(private val name: String?, val firefoxBased: Boolean) {
        override fun toString() = name
            ?: I8N.text(if (firefoxBased) "BROWSER_EXT_OTHER_FIREFOX_BASED" else "BROWSER_EXT_OTHER_CHROMIUM_BASED")
    }

    /**
     * One browser tile: large brand icon above the name, a rounded fill on hover. Clicking it (or
     * Space/Enter when focused) opens [url], XDM's install page for it, in that browser, or explains
     * that it is not installed.
     */
    private class BrowserTile(
        private val browser: Browser,
        icon: BrandIcon,
        accent: Color,
        private val url: String,
    ) : JPanel(BorderLayout(0, 8.px)) {

        private var hovered = false

        init {
            isOpaque = false
            isFocusable = true
            border = ScaledEmptyBorder(14, 6, 12, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "${I8N.text("BROWSER_EXT_INSTALL")} - ${browser.displayName}"

            add(JLabel(BrandFontIcon(icon, ICON_SIZE.px, accent)).apply {
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
            add(JLabel(browser.displayName).apply {
                horizontalAlignment = SwingConstants.CENTER
                font = font.deriveFont(Font.BOLD, 13f.px)
            }, BorderLayout.SOUTH)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = setHovered(true)
                override fun mouseExited(e: MouseEvent) = setHovered(false)
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) launch()
                }
            })
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER) {
                        e.consume()
                        launch()
                    }
                }
            })
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })
        }

        private fun setHovered(value: Boolean) {
            hovered = value
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            if (hovered || isFocusOwner) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 12f.px
                if (hovered) {
                    g2.color = settingsStroke()
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
                }
                if (isFocusOwner) {
                    g2.color = settingsAccentColor()
                    g2.stroke = BasicStroke(1.5f.px)
                    val inset = 1f.px
                    g2.draw(
                        RoundRectangle2D.Float(
                            inset, inset, width - 2 * inset, height - 2 * inset, arc, arc
                        )
                    )
                }
                g2.dispose()
            }
            super.paintComponent(g)
        }

        private fun launch() {
            val owner = SwingUtilities.getWindowAncestor(this)
            // Launching may wait on a helper process (macOS `open`), so keep it off the EDT.
            thread(isDaemon = true, name = "browser-launch") {
                if (!BrowserLauncher.launch(browser, url)) {
                    SwingUtilities.invokeLater { showNotInstalled(owner, browser, url) }
                }
            }
        }
    }

    companion object {
        private const val ICON_SIZE = 36

        /** The picker's entries: popular browsers first, then the two catch-alls. */
        private val OTHER_BROWSERS = listOf(
            OtherBrowser("Arc", false),
            OtherBrowser("Zen", true),
            OtherBrowser("Helium", false),
            OtherBrowser("Opera", false),
            OtherBrowser("Vivaldi", false),
            OtherBrowser("Floorp", true),
            OtherBrowser("LibreWolf", true),
            OtherBrowser("Waterfox", true),
            OtherBrowser(null, false),
            OtherBrowser(null, true),
        )

        /**
         * XDM's install page for [browser] (`chrome`, `firefox`, `edge`, `brave`, `chromium`). It links
         * the store, so it must be opened in that browser. The `utm` tag counts visits from the app.
         */
        fun extensionPageUrl(browser: String) = "$WEBSITE_URL/extension/$browser/?utm_source=xdm-app"
    }
}

/**
 * Read-only [url] field with a copy button beside it. With [fitText] false the field asks for no width
 * of its own (it still stretches to fill the row), so a long URL does not widen the window it sits in.
 */
private class LinkRow(fitText: Boolean = true) : JPanel(BorderLayout(6.px, 0)) {
    private val urlField = JTextField().apply {
        isEditable = false
        if (!fitText) columns = 1
        // Filled but borderless: it reads as a value to copy rather than an input.
        putClientProperty(
            FlatClientProperties.STYLE,
            "arc: 12; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0"
        )
    }

    var url: String
        get() = urlField.text
        set(value) {
            urlField.text = value
            urlField.caretPosition = 0
        }

    init {
        val copyIcon = createIcon(RemixIcon.FILE_COPY_LINE, 17, settingsMutedColor())
        val copiedIcon = createIcon(RemixIcon.CHECKBOX_CIRCLE_LINE, 17, settingsAccentColor())
        lateinit var revert: Timer
        val copy = settingsIconButton(RemixIcon.FILE_COPY_LINE, I8N.text("BROWSER_EXT_COPY")) {}
        copy.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            urlField.selectAll()
            copy.icon = copiedIcon
            copy.toolTipText = I8N.text("BROWSER_EXT_COPIED")
            revert.restart()
        }
        revert = Timer(1500) {
            copy.icon = copyIcon
            copy.toolTipText = I8N.text("BROWSER_EXT_COPY")
        }.apply { isRepeats = false }

        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(urlField, BorderLayout.CENTER)
        add(copy, BorderLayout.EAST)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** Shown when a tile's browser cannot be found: says so, and offers its install page link to copy. */
private fun showNotInstalled(owner: Window?, browser: Browser, url: String) {
    MessageBox.show(
        owner,
        String.format(I8N.text("BROWSER_NOT_INSTALLED_TITLE"), browser.displayName),
        arrayOf(
            JLabel(String.format(I8N.text("BROWSER_NOT_INSTALLED"), browser.displayName)),
            Box.createRigidArea(scaledSize(0, 4)),
            LinkRow().apply { this.url = url },
        ),
    )
}
