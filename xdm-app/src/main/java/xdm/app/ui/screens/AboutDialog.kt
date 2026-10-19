package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.AppTheme
import xdm.app.update.UpdateChecker
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.logoIcon
import xdm.app.utils.openInDefaultBrowser
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import java.awt.*
import javax.swing.*

class AboutDialog(owner: Window?) : JDialog(owner) {

    init {
        setSize(400.px, 380.px)
        title = "About XDM"
        isModal = true
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
                    try {
                        Desktop.getDesktop().browse(java.net.URI("https://xtremedownloadmanager.com"))
                    } catch (_: Exception) {
                    }
                }
            })
        }

        val followSubtitle = JLabel(text("FOLLOW_SUBTITLE")).apply {
            font = font.deriveFont(Font.PLAIN, 12f.px)
            foreground = UIManager.getColor("Label.disabledForeground")
            horizontalAlignment = SwingConstants.CENTER
        }

        val followIcons = JPanel(FlowLayout(FlowLayout.CENTER, 14.px, 0)).apply {
            isOpaque = false
            FOLLOW_LINKS.forEach { add(followIcon(it)) }
        }

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
        panel.add(followSubtitle, gc.apply { insets = scaledInsets(14, 0, 0, 0) })
        panel.add(followIcons, gc.apply { insets = scaledInsets(10, 0, 14, 0) })
        panel.add(website, gc.apply { insets = scaledInsets(6, 0, 6, 0) })

        contentPane.add(panel)
        //pack()
        setLocationRelativeTo(owner)
    }

    /** A brand icon that opens [link] in the browser; muted, and in the accent color on hover. */
    private fun followIcon(link: FollowLink): JLabel {
        val normal = createIcon(link.icon, 18, UIManager.getColor("Label.disabledForeground"))
        val hover = createIcon(link.icon, 18, AppTheme.accent)
        return JLabel(normal).apply {
            toolTipText = link.name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) openInDefaultBrowser(link.url)
                }

                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    icon = hover
                }

                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    icon = normal
                }
            })
        }
    }

    private class FollowLink(val name: String, val icon: RemixIcon, val url: String)

    private companion object {
        val FOLLOW_LINKS = listOf(
            FollowLink("GitHub", RemixIcon.GITHUB_FILL, "https://github.com/subhra74"),
            FollowLink("YouTube", RemixIcon.YOUTUBE_FILL, "https://www.youtube.com/@subhra74"),
            FollowLink("X", RemixIcon.TWITTER_X_FILL, "https://x.com/subhra74"),
            FollowLink("LinkedIn", RemixIcon.LINKEDIN_BOX_FILL, "https://www.linkedin.com/in/subhra74"),
        )
    }
}
