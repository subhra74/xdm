package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.ui.screens.settings.settingsIconButton
import xdm.app.ui.screens.settings.settingsMutedColor
import xdm.app.utils.BrandFontIcon
import xdm.app.utils.BrandIcon
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.Browser
import xdm.app.utils.BrowserLauncher
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import kotlin.concurrent.thread

/**
 * The "install the browser extension" block, shared by the settings Browsers section and the
 * first-run [xdm.app.ui.screens.BrowserSetupDialog]: a 2×2 grid of browser tiles that open the
 * matching store page, and a copyable store link for any other Chromium-based browser.
 */
class BrowserExtensionPanel : JPanel() {

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(JPanel(GridLayout(2, 2, 10, 10)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(BrowserTile(Browser.CHROME, BrandIcon.CHROME, Color(0x4285F4), CHROME_STORE_URL))
            add(BrowserTile(Browser.FIREFOX, BrandIcon.FIREFOX_BROWSER, Color(0xFF7139), FIREFOX_STORE_URL))
            add(BrowserTile(Browser.EDGE, BrandIcon.EDGE, Color(0x24B0C4), EDGE_STORE_URL))
            add(BrowserTile(Browser.BRAVE, BrandIcon.BRAVE, Color(0xFB542B), CHROME_STORE_URL))
        })

        add(Box.createRigidArea(Dimension(0, 16)))
        add(JLabel(I8N.text("BROWSER_EXT_OTHER_CHROMIUM")).apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = settingsMutedColor()
            border = EmptyBorder(0, 2, 7, 0)
        })
        add(linkRow(CHROME_STORE_URL))
    }

    /**
     * A rounded, brand-tinted browser tile: large icon over the name, stronger on hover. A click opens
     * [url] in that browser, or explains that it is not installed.
     */
    private class BrowserTile(
        private val browser: Browser,
        icon: BrandIcon,
        private val accent: Color,
        private val url: String,
    ) : JPanel() {
        private var hovered = false

        init {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(18, 8, 16, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            add(JLabel(BrandFontIcon(icon, ICON_SIZE, accent)).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
            })
            add(JLabel(browser.displayName).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                font = font.deriveFont(Font.BOLD, 14f)
                border = EmptyBorder(10, 0, 0, 0)
            })

            val mouseListener = object : MouseAdapter() {
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

                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) launch()
                }
            }
            addMouseListener(mouseListener)
            components.forEach { it.addMouseListener(mouseListener) }
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

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Always tinted and outlined in the brand color; hovering deepens both.
            g2.color = Color(accent.red, accent.green, accent.blue, if (hovered) 40 else 20)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.color = Color(accent.red, accent.green, accent.blue, if (hovered) 230 else 150)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    companion object {
        private const val ICON_SIZE = 56

        // TODO: replace with the published store listings.
        const val CHROME_STORE_URL = "https://chromewebstore.google.com/detail/xdm/placeholder"
        const val FIREFOX_STORE_URL = "https://addons.mozilla.org/firefox/addon/xdm-placeholder/"
        const val EDGE_STORE_URL = "https://microsoftedge.microsoft.com/addons/detail/xdm/placeholder"
    }
}

/** Read-only [url] field with a copy button beside it. */
private fun linkRow(url: String): JPanel {
    val field = JTextField(url).apply {
        isEditable = false
        // Filled but borderless: it reads as a value to copy rather than an input.
        putClientProperty(
            FlatClientProperties.STYLE,
            "arc: 12; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; innerOutlineWidth: 0"
        )
    }
    val copyIcon = createIcon(RemixIcon.FILE_COPY_LINE, 17, settingsMutedColor())
    val copiedIcon = createIcon(RemixIcon.CHECKBOX_CIRCLE_LINE, 17, settingsAccentColor())
    lateinit var revert: Timer
    val copy = settingsIconButton(RemixIcon.FILE_COPY_LINE, I8N.text("BROWSER_EXT_COPY")) {}
    copy.addActionListener {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        field.selectAll()
        copy.icon = copiedIcon
        copy.toolTipText = I8N.text("BROWSER_EXT_COPIED")
        revert.restart()
    }
    revert = Timer(1500) {
        copy.icon = copyIcon
        copy.toolTipText = I8N.text("BROWSER_EXT_COPY")
    }.apply { isRepeats = false }

    return JPanel(BorderLayout(6, 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(field, BorderLayout.CENTER)
        add(copy, BorderLayout.EAST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}

/** Shown when a tile's browser cannot be found: says so, and offers its store link to copy. */
private fun showNotInstalled(owner: Window?, browser: Browser, url: String) {
    JOptionPane.showMessageDialog(
        owner,
        arrayOf(
            JLabel(String.format(I8N.text("BROWSER_NOT_INSTALLED"), browser.displayName)),
            Box.createRigidArea(Dimension(0, 4)),
            linkRow(url),
        ),
        String.format(I8N.text("BROWSER_NOT_INSTALLED_TITLE"), browser.displayName),
        JOptionPane.INFORMATION_MESSAGE,
    )
}
