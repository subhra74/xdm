package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N
import xdm.app.ui.screens.settings.settingsAccentColor
import xdm.app.ui.screens.settings.settingsButton
import xdm.app.ui.screens.settings.settingsIconButton
import xdm.app.ui.screens.settings.settingsMutedColor
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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.CompoundBorder
import javax.swing.border.MatteBorder
import kotlin.concurrent.thread

/**
 * The "install the browser extension" block, shared by the settings Browsers section and the
 * first-run [xdm.app.ui.screens.BrowserSetupDialog]: a list of browsers, each with an Install button
 * that opens XDM's install page for that browser, and a copyable link for any other Chromium-based
 * browser. The app never links a store directly: the website pages carry the store links, so a
 * listing can move without an app update, and they walk the user through the steps after installing.
 */
class BrowserExtensionPanel : JPanel() {

    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        listOf(
            BrowserRow(Browser.CHROME, BrandIcon.CHROME, Color(0x4285F4), extensionPageUrl("chrome")),
            BrowserRow(Browser.FIREFOX, BrandIcon.FIREFOX_BROWSER, Color(0xFF7139), extensionPageUrl("firefox")),
            BrowserRow(Browser.EDGE, BrandIcon.EDGE, Color(0x24B0C4), extensionPageUrl("edge")),
            BrowserRow(Browser.BRAVE, BrandIcon.BRAVE, Color(0xFB542B), extensionPageUrl("brave")),
        ).forEachIndexed { i, row ->
            if (i > 0) {
                row.border = CompoundBorder(MatteBorder(1, 0, 0, 0, settingsStroke()), row.border)
            }
            add(row)
        }

        add(Box.createRigidArea(scaledSize(0, 16)))
        add(JLabel(I8N.text("BROWSER_EXT_OTHER_CHROMIUM")).apply {
            alignmentX = LEFT_ALIGNMENT
            foreground = settingsMutedColor()
            border = ScaledEmptyBorder(0, 2, 7, 0)
        })
        add(linkRow(extensionPageUrl("chromium")))
    }

    /**
     * One browser in the list: large brand icon, then the name, then an Install button at the right
     * edge that opens [url] (XDM's install page for it) in that browser, or explains that it is not installed.
     */
    private class BrowserRow(
        private val browser: Browser,
        icon: BrandIcon,
        accent: Color,
        private val url: String,
    ) : JPanel(BorderLayout(14.px, 0)) {

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = ScaledEmptyBorder(15, 2, 15, 2)

            add(JLabel(BrandFontIcon(icon, ICON_SIZE.px, accent)), BorderLayout.WEST)
            add(JLabel(browser.displayName).apply {
                font = font.deriveFont(Font.BOLD, 16f.px)
            }, BorderLayout.CENTER)
            add(JPanel(GridBagLayout()).apply {
                // Keeps the button at its natural height, centred against the taller icon.
                isOpaque = false
                add(settingsButton(I8N.text("BROWSER_EXT_INSTALL")) { launch() })
            }, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
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
        private const val ICON_SIZE = 28

        /**
         * XDM's install page for [browser] (`chrome`, `firefox`, `edge`, `brave`, `chromium`). It links
         * the store, so it must be opened in that browser. The `utm` tag counts visits from the app.
         */
        fun extensionPageUrl(browser: String) = "$WEBSITE_URL/extension/$browser/?utm_source=xdm-app"
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

    return JPanel(BorderLayout(6.px, 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(field, BorderLayout.CENTER)
        add(copy, BorderLayout.EAST)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}

/** Shown when a tile's browser cannot be found: says so, and offers its install page link to copy. */
private fun showNotInstalled(owner: Window?, browser: Browser, url: String) {
    JOptionPane.showMessageDialog(
        owner,
        arrayOf(
            JLabel(String.format(I8N.text("BROWSER_NOT_INSTALLED"), browser.displayName)),
            Box.createRigidArea(scaledSize(0, 4)),
            linkRow(url),
        ),
        String.format(I8N.text("BROWSER_NOT_INSTALLED_TITLE"), browser.displayName),
        JOptionPane.INFORMATION_MESSAGE,
    )
}
