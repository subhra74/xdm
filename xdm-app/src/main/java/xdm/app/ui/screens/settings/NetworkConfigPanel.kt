package xdm.app.ui.screens.settings

import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.RemixIcon
import xdm.core.CoreConfig
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/** How XDM reaches the network: proxy, timeouts and certificate handling. */
class NetworkConfigPanel : SettingsPanel() {
    private enum class ProxyMode { NONE, HTTP, SOCKS }

    private val optProxy = SettingsOptionGroup(
        listOf(
            SettingsOption(ProxyMode.NONE, I8N.text("MSG_NO_PROXY"), RemixIcon.CLOSE_CIRCLE_LINE),
            SettingsOption(ProxyMode.HTTP, I8N.text("MSG_HTTP_PROXY"), RemixIcon.GLOBAL_LINE),
            SettingsOption(ProxyMode.SOCKS, I8N.text("MSG_SOCKS_PROXY"), RemixIcon.SERVER_LINE),
        )
    )
    private val txtProxyHost = rounded(JTextField()).apply { columns = 10 }
    private val spProxyPort = rounded(JSpinner(SpinnerNumberModel(8080, 1, 65535, 1))).apply {
        preferredSize = Dimension(110, preferredSize.height)
        maximumSize = preferredSize
    }
    private val txtProxyUser = rounded(JTextField()).apply { columns = 10 }
    private val txtProxyPass = rounded(JPasswordField()).apply { columns = 10 }
    private val proxyFields: JComponent

    private val spReadTimeout = rounded(
        JSpinner(
            SpinnerNumberModel(
                CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS,
                AppConfig.MIN_READ_TIMEOUT_SECONDS,
                AppConfig.MAX_READ_TIMEOUT_SECONDS,
                5
            )
        )
    ).apply {
        preferredSize = Dimension(110, preferredSize.height)
        maximumSize = preferredSize
    }
    private val tglIgnoreCertErrors = SettingsToggle()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_NETWORK")))

        proxyFields = Box.createVerticalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            // The gap lives inside the collapsible box so hiding it leaves no dead space.
            add(Box.createRigidArea(Dimension(0, 16)))
            add(labelledField(I8N.text("MSG_PROXY_HOST"), txtProxyHost))
            add(Box.createRigidArea(Dimension(0, 12)))
            add(settingsLeftAligned(fieldWithCaption(I8N.text("MSG_PROXY_PORT"), spProxyPort)))
            add(Box.createRigidArea(Dimension(0, 12)))
            add(labelledField(I8N.text("MSG_PROXY_USER"), txtProxyUser))
            add(Box.createRigidArea(Dimension(0, 12)))
            add(labelledField(I8N.text("MSG_PROXY_PASS"), txtProxyPass))
        }

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_PROXY"),
                // Tiles and fields share one row: when the fields are hidden the row shrinks,
                // rather than leaving a hairline and an empty band behind.
                settingsFullRow(
                    null, I8N.text("SETTINGS_SEC_PROXY_SUB"),
                    Box.createVerticalBox().apply {
                        alignmentX = LEFT_ALIGNMENT
                        add(optProxy)
                        add(proxyFields)
                    }
                ),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ADV_NETWORK"),
                settingsRow(
                    I8N.text("MSG_READ_TIMEOUT"),
                    I8N.text("MSG_READ_TIMEOUT_HINT"),
                    Box.createHorizontalBox().apply {
                        add(spReadTimeout)
                        add(Box.createRigidArea(Dimension(8, 0)))
                        add(JLabel(I8N.text("MSG_SECONDS")).apply { foreground = settingsMutedColor() })
                    }
                ),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_SECURITY"),
                settingsRow(
                    I8N.text("MSG_IGNORE_CERT_ERRORS"),
                    I8N.text("MSG_IGNORE_CERT_ERRORS_HINT"),
                    tglIgnoreCertErrors
                ),
            )
        )

        add(Box.createVerticalGlue())

        optProxy.onChange = { updateProxySettings() }
        tglIgnoreCertErrors.addActionListener { confirmIgnoreCertErrors() }
    }

    /** A caption above a full-width field. */
    private fun labelledField(caption: String, field: JComponent): JComponent =
        Box.createVerticalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(settingsLeftAligned(settingsHint(caption)))
            add(Box.createRigidArea(Dimension(0, 5)))
            field.maximumSize = Dimension(Int.MAX_VALUE, field.preferredSize.height)
            field.alignmentX = LEFT_ALIGNMENT
            add(field)
        }

    /** A caption above a fixed-width field, so a port box does not stretch across the card. */
    private fun fieldWithCaption(caption: String, field: JComponent): JComponent =
        Box.createVerticalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(settingsLeftAligned(settingsHint(caption)))
            add(Box.createRigidArea(Dimension(0, 5)))
            field.alignmentX = LEFT_ALIGNMENT
            add(field)
        }

    private fun updateProxySettings() {
        proxyFields.isVisible = optProxy.selected != ProxyMode.NONE
        revalidate()
        repaint()
    }

    /** Turning certificate checks off is risky, so ask before switching it on. */
    private fun confirmIgnoreCertErrors() {
        if (!tglIgnoreCertErrors.isSelected) return
        val choice = JOptionPane.showConfirmDialog(
            this,
            I8N.text("MSG_IGNORE_CERT_ERRORS_CONFIRM"),
            I8N.text("MSG_IGNORE_CERT_ERRORS"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice != JOptionPane.YES_OPTION) {
            tglIgnoreCertErrors.isSelected = false
        }
    }

    fun load() {
        val config = AppContext.config
        optProxy.selected = when {
            config.useProxy -> ProxyMode.HTTP
            config.socksProxy -> ProxyMode.SOCKS
            else -> ProxyMode.NONE
        }
        updateProxySettings()
        txtProxyHost.text = config.proxyHost
        spProxyPort.value = config.proxyPort
        txtProxyUser.text = config.proxyUser
        txtProxyPass.text = config.proxyPass
        spReadTimeout.value = config.readTimeoutSeconds
        tglIgnoreCertErrors.isSelected = config.ignoreCertErrors
    }

    fun save() {
        val config = AppContext.config
        val hasHost = txtProxyHost.text.isNotEmpty()
        config.useProxy = optProxy.selected == ProxyMode.HTTP && hasHost
        config.socksProxy = optProxy.selected == ProxyMode.SOCKS && hasHost
        config.proxyHost = txtProxyHost.text
        config.proxyPort = spProxyPort.value as Int
        config.proxyUser = txtProxyUser.text
        config.proxyPass = String(txtProxyPass.password)
        config.readTimeoutSeconds = spReadTimeout.value as Int
        config.ignoreCertErrors = tglIgnoreCertErrors.isSelected
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)
}
