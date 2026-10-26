package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.AppTheme
import xdm.app.update.UpdateChecker
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.SingleWindows
import xdm.app.utils.WEBSITE_URL
import xdm.app.utils.createIcon
import xdm.app.utils.logoIcon
import xdm.app.utils.openInDefaultBrowser
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.shownWindow
import xdm.app.utils.windowIconImages
import java.awt.*
import javax.swing.*

/**
 * Not modal (docs/windows-and-modality.md): nothing here needs to hold up the rest of the app. Open it
 * with [open]; a null [owner] (macOS app menu while XDM is in the tray) makes it a window of its own.
 */
class AboutDialog private constructor(owner: Window?) : JDialog(owner) {

    companion object {
        /** Opens About, or brings the open one forward. Owned by [parent]'s window while that is on screen. EDT only. */
        fun open(parent: Component?) = SingleWindows.show(AboutDialog::class) { AboutDialog(shownWindow(parent)) }

        private const val GITHUB_REPO_URL = "https://github.com/subhra74/xdm"
    }

    init {
        setSize(400.px, 380.px)
        title = "About XDM"
        if (owner == null) iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = false

        val panel = JPanel(GridBagLayout()).apply {
            border = ScaledEmptyBorder(30, 40, 30, 40)
        }

        val logoIcon = try {
            logoIcon(96.px)
        } catch (e: Exception) {
            null
        }

        val logoLabel = JLabel(logoIcon).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val version = UpdateChecker.loadConfig()?.currentVersion ?: ""
        val appName = JLabel("Xtreme Download Manager $version".trim()).apply {
            font = font.deriveFont(Font.PLAIN, 16f.px)
            horizontalAlignment = SwingConstants.CENTER
        }

        val copyright = JLabel("© 2013 - 2026 Subhra Das Gupta").apply {
            font = font.deriveFont(Font.PLAIN, 13f.px)
            horizontalAlignment = SwingConstants.CENTER
        }

        val website = JLabel("xtremedownloadmanager.com").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = UIManager.getColor("ProgressBar.foreground")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    openInDefaultBrowser(WEBSITE_URL)
                }
            })
        }

        val githubLink = githubStarLink()

        val gc = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = scaledInsets(6, 0, 6, 0)
            weightx = 1.0
        }

        panel.add(logoLabel, gc)
        panel.add(appName, gc)
        panel.add(copyright, gc)
        panel.add(githubLink, gc.apply { insets = scaledInsets(14, 0, 14, 0) })
        panel.add(website, gc.apply { insets = scaledInsets(6, 0, 6, 0) })

        contentPane.add(panel)
        //pack()
        setLocationRelativeTo(owner)
    }

    /** The GitHub mark and "Star on GitHub", opening the repository; muted, and in the accent color on hover. */
    private fun githubStarLink(): JLabel {
        val muted = UIManager.getColor("Label.disabledForeground")
        val normal = createIcon(RemixIcon.GITHUB_FILL, 18, muted)
        val hover = createIcon(RemixIcon.GITHUB_FILL, 18, AppTheme.accent)
        return JLabel(text("ABOUT_STAR_GITHUB"), normal, SwingConstants.CENTER).apply {
            iconTextGap = 6.px
            foreground = muted
            toolTipText = GITHUB_REPO_URL
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) openInDefaultBrowser(GITHUB_REPO_URL)
                }

                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    icon = hover
                    foreground = AppTheme.accent
                }

                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    icon = normal
                    foreground = muted
                }
            })
        }
    }
}
