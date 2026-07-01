package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.fixHeight
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class NetworkConfigPanel : SettingsPanel() {
    private val cmbSplit = JComboBox<Int>().apply {
        fixHeight(this)
        for (i in intArrayOf(1, 2, 4, 8, 16, 32, 64)) {
            addItem(i)
        }
    }
    private val cmbRetry = JComboBox<Int>().apply {
        fixHeight(this)
        for (i in 1..99) {
            addItem(i)
        }
    }

    private val txtProxyHost = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val spProxyPort = JSpinner(SpinnerNumberModel(8080, 1, 65535, 1)).apply {
        fixHeight(this)
        preferredSize = Dimension(120, preferredSize.height)
        maximumSize = Dimension(120, preferredSize.height)
    }
    private val txtProxyUser = JTextField().apply { columns = 10 }
    private val txtProxyPass = JPasswordField()
    private val radNoProxy = JRadioButton(I8N.text("MSG_NO_PROXY"))
    private val radHttpProxy = JRadioButton(I8N.text("MSG_HTTP_PROXY"))
    private val radSocksProxy = JRadioButton(I8N.text("MSG_SOCKS_PROXY"))
    private val lblProxyHost = JLabel(I8N.text("MSG_PROXY_HOST"))
    val lblProxyPort = JLabel(I8N.text("MSG_PROXY_PORT"))

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        add(settingsTitle(I8N.text("SETTINGS_NETWORK")))

        // Connection
        add(
            settingsCard(
                "arrow-up-down-fill.svg", I8N.text("SETTINGS_SEC_CONNECTION"),
                settingsRow(JLabel(I8N.text("MSG_MAX_SPLIT")), cmbSplit),
                settingsRow(JLabel(I8N.text("MSG_MAX_RETRY")), cmbRetry),
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Proxy
        val buttonGroup = ButtonGroup()
        buttonGroup.add(radNoProxy)
        buttonGroup.add(radHttpProxy)
        buttonGroup.add(radSocksProxy)

        val proxyBox = Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(radNoProxy)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(radHttpProxy)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(radSocksProxy)
            add(Box.createHorizontalGlue())
        }

        radNoProxy.addActionListener { updateProxySettings() }
        radHttpProxy.addActionListener { updateProxySettings() }
        radSocksProxy.addActionListener { updateProxySettings() }

        add(
            settingsCard(
                "global-fill.svg", I8N.text("SETTINGS_SEC_PROXY"),
                proxyBox,
                settingsRow(lblProxyHost, txtProxyHost),
                settingsRow(lblProxyPort, spProxyPort),
            )
        )

        add(Box.createVerticalGlue())

        load()
    }

    private fun updateProxySettings() {
        val showFields = radHttpProxy.isSelected || radSocksProxy.isSelected
        lblProxyHost.isVisible = showFields
        lblProxyPort.isVisible = showFields
        txtProxyHost.isVisible = showFields
        spProxyPort.isVisible = showFields
    }

    fun load() {
        val config = AppContext.config
        cmbSplit.selectedItem = config.maxSegments
        cmbRetry.selectedItem = config.maxRetries
        if (config.useProxy) {
            radHttpProxy.isSelected = true
        } else if (config.socksProxy) {
            radSocksProxy.isSelected = true
        } else {
            radNoProxy.isSelected = true
        }
        updateProxySettings()
        txtProxyHost.text = config.proxyHost
        spProxyPort.value = config.proxyPort
        txtProxyUser.text = config.proxyUser
        txtProxyPass.text = config.proxyPass
    }

    fun save() {
        val config = AppContext.config
        config.maxSegments = cmbSplit.selectedItem as Int
        config.maxRetries = cmbRetry.selectedItem as Int
        if (radHttpProxy.isSelected && txtProxyHost.text.isNotEmpty()) {
            config.useProxy = true
            config.socksProxy = false
        } else if (radSocksProxy.isSelected && txtProxyHost.text.isNotEmpty()) {
            config.useProxy = false
            config.socksProxy = true
        } else {
            config.useProxy = false
            config.socksProxy = false
        }
        config.proxyHost = txtProxyHost.text
        config.proxyPort = spProxyPort.value as Int
        config.proxyUser = txtProxyUser.text
        config.proxyPass = String(txtProxyPass.password)
    }

    override fun getInsets(): Insets {
        return Insets(10, 12, 12, 12)
    }
}
