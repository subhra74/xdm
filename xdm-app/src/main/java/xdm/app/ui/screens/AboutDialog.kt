package xdm.app.ui.screens

import com.formdev.flatlaf.extras.FlatSVGIcon
import xdm.app.AppContext
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class AboutDialog(owner: Window?) : JDialog(owner) {

    init {
        setSize(400, 350)
        title = "About XDM"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isResizable = false

        val panel = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(30, 40, 30, 40)
        }

        val logoIcon = try {
            val svgIcon = FlatSVGIcon(AppContext::class.java.getResourceAsStream("/icons/xdm-logo.svg"))
            svgIcon.derive(96, 96)
        } catch (e: Exception) {
            null
        }

        val logoLabel = JLabel(logoIcon).apply {
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val appName = JLabel("Xtreme Download Manager 9.0.1").apply {
            font = font.deriveFont(Font.PLAIN, 16f)
            horizontalAlignment = SwingConstants.CENTER
        }

        val copyright = JLabel("© 2013 - 2026 Subhra Das Gupta").apply {
            font = font.deriveFont(Font.PLAIN, 13f)
            horizontalAlignment = SwingConstants.CENTER
        }

        val website = JLabel("www.xtremedownloadmanager.com").apply {
            horizontalAlignment = SwingConstants.CENTER
            foreground = UIManager.getColor("ProgressBar.foreground")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    try {
                        Desktop.getDesktop().browse(java.net.URI("https://www.xtremedownloadmanager.com"))
                    } catch (_: Exception) {
                    }
                }
            })
        }

        val osName = System.getProperty("os.name") ?: ""
        val osVersion = System.getProperty("os.version") ?: ""
        val javaVersion = System.getProperty("java.version") ?: ""

        val osInfo = JLabel("$osName $osVersion").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
            horizontalAlignment = SwingConstants.CENTER
        }

        val javaInfo = JLabel("Java $javaVersion").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
            horizontalAlignment = SwingConstants.CENTER
        }

        val gc = GridBagConstraints().apply {
            gridx = 0; gridy = GridBagConstraints.RELATIVE
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = Insets(6, 0, 6, 0)
            weightx = 1.0
        }

        panel.add(logoLabel, gc)
        panel.add(appName, gc)
        panel.add(copyright, gc)
        panel.add(website, gc)

//        val sep = JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1) }
//        panel.add(sep, gc)

        panel.add(osInfo, gc)
        panel.add(javaInfo, gc)

        contentPane.add(panel)
        //pack()
        setLocationRelativeTo(owner)
    }
}
