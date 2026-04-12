package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class NetworkConfigPanel : JPanel() {
    private val cmbSplit = JComboBox<Int>().apply {
        for (i in intArrayOf(1, 2, 4, 8, 16, 32, 64)) {
            addItem(i)
        }
    }
    private val cmbRetry = JComboBox<Int>().apply {
        for (i in 1..99) {
            addItem(i)
        }
    }
    private val chkProxy = JCheckBox(I8N.text("MSG_USE_PROXY"))
    private val txtProxyHost = JTextField().apply { columns = 10 }
    private val spProxyPort = JSpinner(SpinnerNumberModel(8080, 1, 65535, 1))
    private val txtProxyUser = JTextField().apply { columns = 10 }
    private val txtProxyPass = JPasswordField()

    init {
        val gridBagLayout = GridBagLayout()
        gridBagLayout.columnWidths = intArrayOf(0, 0, 0, 0)
        gridBagLayout.rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        gridBagLayout.columnWeights = doubleArrayOf(0.0, 1.0, 1.0, Double.Companion.MIN_VALUE)
        gridBagLayout.rowWeights =
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.Companion.MIN_VALUE)
        setLayout(gridBagLayout)

        val lblTitle = JLabel(I8N.text("SETTINGS_NETWORK")).apply { font = font.deriveFont(16.0f) }
        val gbclblTitle = GridBagConstraints()
        gbclblTitle.anchor = GridBagConstraints.WEST
        gbclblTitle.ipady = 10
        gbclblTitle.gridwidth = 3
        gbclblTitle.insets = Insets(0, 0, 5, 0)
        gbclblTitle.gridx = 0
        gbclblTitle.gridy = 0
        add(lblTitle, gbclblTitle)

        val lblSplit = JLabel(I8N.text("MSG_MAX_SPLIT"))
        val gbclblSplit = GridBagConstraints()
        gbclblSplit.insets = Insets(0, 0, 5, 5)
        gbclblSplit.anchor = GridBagConstraints.WEST
        gbclblSplit.gridx = 0
        gbclblSplit.gridy = 1
        add(lblSplit, gbclblSplit)

        val gbcCmbsplit = GridBagConstraints()
        gbcCmbsplit.insets = Insets(0, 0, 5, 0)
        gbcCmbsplit.fill = GridBagConstraints.HORIZONTAL
        gbcCmbsplit.gridx = 2
        gbcCmbsplit.gridy = 1
        add(cmbSplit, gbcCmbsplit)

        val lblRetry = JLabel(I8N.text("MSG_MAX_RETRY"))
        val gbclblRetry = GridBagConstraints()
        gbclblRetry.anchor = GridBagConstraints.WEST
        gbclblRetry.insets = Insets(0, 0, 5, 5)
        gbclblRetry.gridx = 0
        gbclblRetry.gridy = 2
        add(lblRetry, gbclblRetry)

        val gbcCmbretry = GridBagConstraints()
        gbcCmbretry.insets = Insets(0, 0, 5, 0)
        gbcCmbretry.fill = GridBagConstraints.HORIZONTAL
        gbcCmbretry.gridx = 2
        gbcCmbretry.gridy = 2
        add(cmbRetry, gbcCmbretry)

        val gbcChkproxy = GridBagConstraints()
        gbcChkproxy.anchor = GridBagConstraints.WEST
        gbcChkproxy.insets = Insets(0, 0, 5, 5)
        gbcChkproxy.gridx = 0
        gbcChkproxy.gridy = 4
        add(chkProxy, gbcChkproxy)

        val lblProxyHost = JLabel(I8N.text("MSG_PROXY_HOST"))
        val gbcchkProxy = GridBagConstraints()
        gbcchkProxy.anchor = GridBagConstraints.EAST
        gbcchkProxy.insets = Insets(0, 0, 5, 5)
        gbcchkProxy.gridx = 0
        gbcchkProxy.gridy = 5
        add(lblProxyHost, gbcchkProxy)

        val gbctxtProxyHost = GridBagConstraints()
        gbctxtProxyHost.insets = Insets(0, 0, 5, 5)
        gbctxtProxyHost.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyHost.gridx = 1
        gbctxtProxyHost.gridy = 5
        add(txtProxyHost, gbctxtProxyHost)

        val lblProxyPort = JLabel(I8N.text("MSG_PROXY_PORT"))
        val gbclblProxyPort = GridBagConstraints()
        gbclblProxyPort.anchor = GridBagConstraints.EAST
        gbclblProxyPort.insets = Insets(0, 0, 5, 5)
        gbclblProxyPort.gridx = 0
        gbclblProxyPort.gridy = 6
        add(lblProxyPort, gbclblProxyPort)

        val gbcspProxyPort = GridBagConstraints()
        gbcspProxyPort.anchor = GridBagConstraints.WEST
        gbcspProxyPort.insets = Insets(0, 0, 5, 5)
        gbcspProxyPort.gridx = 1
        gbcspProxyPort.gridy = 6
        add(spProxyPort, gbcspProxyPort)

        val lblProxyUser = JLabel(I8N.text("MSG_PROXY_USER"))
        val gbclblProxyUser = GridBagConstraints()
        gbclblProxyUser.anchor = GridBagConstraints.EAST
        gbclblProxyUser.insets = Insets(0, 0, 5, 5)
        gbclblProxyUser.gridx = 0
        gbclblProxyUser.gridy = 7
        add(lblProxyUser, gbclblProxyUser)

        val gbctxtProxyUser = GridBagConstraints()
        gbctxtProxyUser.insets = Insets(0, 0, 5, 5)
        gbctxtProxyUser.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyUser.gridx = 1
        gbctxtProxyUser.gridy = 7
        add(txtProxyUser, gbctxtProxyUser)

        val lblProxyPass = JLabel(I8N.text("MSG_PROXY_PASS"))
        val gbclblProxyPass = GridBagConstraints()
        gbclblProxyPass.anchor = GridBagConstraints.EAST
        gbclblProxyPass.insets = Insets(0, 0, 5, 5)
        gbclblProxyPass.gridx = 0
        gbclblProxyPass.gridy = 8
        add(lblProxyPass, gbclblProxyPass)

        val gbctxtProxyPass = GridBagConstraints()
        gbctxtProxyPass.insets = Insets(0, 0, 5, 5)
        gbctxtProxyPass.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyPass.gridx = 1
        gbctxtProxyPass.gridy = 8
        add(txtProxyPass, gbctxtProxyPass)

        load()
    }

    private fun load() {
        val config = AppContext.config
        cmbSplit.selectedItem = config.maxSegments
        cmbRetry.selectedItem = config.maxRetries
        chkProxy.isSelected = config.useProxy
        txtProxyHost.text = config.proxyHost
        spProxyPort.value = config.proxyPort
        txtProxyUser.text = config.proxyUser
        txtProxyPass.text = config.proxyPass
    }

    fun save() {
        val config = AppContext.config
        config.maxSegments = cmbSplit.selectedItem as Int
        config.maxRetries = cmbRetry.selectedItem as Int
        config.useProxy = chkProxy.isSelected
        config.proxyHost = txtProxyHost.text
        config.proxyPort = spProxyPort.value as Int
        config.proxyUser = txtProxyUser.text
        config.proxyPass = String(txtProxyPass.password)
    }

    override fun getInsets(): Insets {
        return Insets(15, 10, 10, 10)
    }
}
