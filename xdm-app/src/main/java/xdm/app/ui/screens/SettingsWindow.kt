package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.I8N.text
import xdm.app.utils.createSVGIcon
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder


class SettingsWindow(parent: Window) : JDialog(parent) {
    private val card = CardLayout()
    private val panelHolder = JPanel(card)

    private val panelIndices = arrayOf("GEN_PAN", "BRM_PAN", "NET_PAN", "ADV_PAN")

    private val leftList =
        JList(arrayOf(text("SETTINGS_GENERAL"), text("SETTINGS_MONITORING"), text("SETTINGS_NETWORK"), text("MSG_ADV_TITLE"))).apply {
            putClientProperty(FlatClientProperties.STYLE, "cellMargins: 10,15,10,25")
            addListSelectionListener { card.show(panelHolder, panelIndices[selectedIndex]) }
        }

    init {
        size = Dimension(550, 450)
        add(leftList, BorderLayout.WEST)
        add(panelHolder, BorderLayout.CENTER)

        panelHolder.add(GeneralPanel(), "GEN_PAN")
        panelHolder.add(JScrollPane(BrowserMonitorPanel()).apply { border = EmptyBorder(0, 0, 0, 0) }, "BRM_PAN")
        panelHolder.add(NetworkConfigPanel(), "NET_PAN")
        panelHolder.add(AdvancedConfigPanel(), "ADV_PAN")
        leftList.selectedIndex = 0

    }

}

class GeneralPanel : JPanel() {
    private val txtTmpDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val txtDwnDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val spModel = SpinnerNumberModel(100, 0, 10000000, 1)
    private val spnSpeedLimiter = JSpinner(spModel).apply {
        fixHeight(this)
        preferredSize = Dimension(120, preferredSize.height)
        maximumSize = Dimension(120, preferredSize.height)
    }
    private val chkSpeedLimiter = JCheckBox(text("SPEED_LIMIT_TITLE"))
    private val chkShowDwnPrg = JCheckBox(text("SHOW_DWN_PRG")).apply {
        padding(this, 10)
        alignmentX = LEFT_ALIGNMENT
    }
    private val chkShowComplete = JCheckBox(text("SHOW_DWN_COMPLETE")).apply { padding(this, 10) }
    private val chkStartAutoDwn = JCheckBox(text("LBL_START_AUTO")).apply { padding(this, 10) }
    private val chkOverwrite = JCheckBox(text("LBL_OVERWRITE_EXISTING")).apply { padding(this, 10) }
    private val btnBrowse1 = JButton("...")
    private val btnBrowse2 = JButton("...")
    private val cmbMaxConn = JComboBox<Any?>().apply {
        fixHeight(this)
    }

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        val lblTitle = JLabel(text("SETTINGS_GENERAL")).apply {
            padding(this, 10, topPadding = true)
            font = font.deriveFont(16.0f)
        }
        add(lblTitle)

        add(chkShowDwnPrg)

        add(chkShowComplete)

        add(chkStartAutoDwn)

        add(chkOverwrite)

        val panelSp = JPanel().apply { padding(this, 10) }
        panelSp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelSp)
        panelSp.setLayout(BoxLayout(panelSp, BoxLayout.X_AXIS))

        panelSp.add(chkSpeedLimiter)
        panelSp.add(Box.createHorizontalGlue())
        panelSp.add(spnSpeedLimiter)


        val lblTempFolder = JLabel(text("LBL_TEMP_FOLDER")).apply { padding(this, 5) }
        add(lblTempFolder)

        val panelTemp = JPanel().apply { padding(this, 10) }
        panelTemp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelTemp)
        panelTemp.setLayout(BoxLayout(panelTemp, BoxLayout.X_AXIS))

        panelTemp.add(txtTmpDir)

        panelTemp.add(Box.createRigidArea(Dimension(10, 10)))

        panelTemp.add(btnBrowse1)

        val lblDefFolder = JLabel(text("SETTINGS_FOLDER")).apply { padding(this, 5) }
        add(lblDefFolder)

        val panelDefFolder = JPanel().apply { padding(this, 10) }
        panelDefFolder.setAlignmentX(LEFT_ALIGNMENT)
        add(panelDefFolder)
        panelDefFolder.setLayout(BoxLayout(panelDefFolder, BoxLayout.X_AXIS))

        panelDefFolder.add(txtDwnDir)

        panelDefFolder.add(Box.createRigidArea(Dimension(10, 10)))

        panelDefFolder.add(btnBrowse2)

        val panelMaxConn = Box.createHorizontalBox()
        panelMaxConn.setAlignmentX(LEFT_ALIGNMENT)
        add(panelMaxConn)

        val lblMaxConn = JLabel(text("MSG_MAX_DOWNLOAD"))
        panelMaxConn.add(lblMaxConn)
        panelMaxConn.add(Box.createHorizontalGlue())
        panelMaxConn.add(cmbMaxConn)

        add(Box.createHorizontalGlue())
    }

    override fun getInsets(): Insets {
        return Insets(10, 10, 10, 10)
    }
}

class BrowserMonitorPanel : JPanel() {
    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        val lblBrowerMon = JLabel(text("BROWSER_MONITORING")).apply {
            setAlignmentX(LEFT_ALIGNMENT)
            padding(this, 10, topPadding = true)
            font = font.deriveFont(16.0f)
        }
        add(lblBrowerMon)

        val p1 = JPanel().apply {
            padding(this, 5, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p1)
        p1.setLayout(GridLayout(1, 4, 0, 20))

        val lblChrome = JLabel(createSVGIcon("chrome-fill.svg", 64, Color.GRAY)).apply {
            text = "Chrome"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblChrome)

        val lblFirefox = JLabel(createSVGIcon("firefox-fill.svg", 64, Color.GRAY)).apply {
            text = "Firefox"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblFirefox)

        val lblEdge = JLabel(createSVGIcon("edge-new-fill.svg", 64, Color.GRAY)).apply {
            text = "Edge"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblEdge)

        val lblOther = JLabel(createSVGIcon("global-fill.svg", 64, Color.GRAY)).apply {
            text = "Other"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblOther)

        val lblFileExt = JLabel(text("DESC_FILETYPES")).apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblFileExt)

        val txtFileExt = JTextArea().apply {
            rows = 3
            wrapStyleWord = true
            lineWrap = true
        }
        add(JScrollPane(txtFileExt).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p2 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p2)
        p2.setLayout(BoxLayout(p2, BoxLayout.X_AXIS))

        val btnExtDef = JButton(text("DESC_DEF"))
        p2.add(btnExtDef)

        val lblVidExt = JLabel(text("DESC_VIDEOTYPES")).apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblVidExt)

        val txtVidExt = JTextArea().apply {
            rows = 3
            wrapStyleWord = true
            lineWrap = true
        }
        add(JScrollPane(txtVidExt).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p3 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p3)
        p3.setLayout(BoxLayout(p3, BoxLayout.X_AXIS))

        val btnVidExtDef = JButton(text("DESC_DEF"))
        p3.add(btnVidExtDef)

        val p4 = JPanel().apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p4)
        p4.setLayout(BoxLayout(p4, BoxLayout.X_AXIS))

        val lblMinVidSize = JLabel(text("LBL_MIN_VIDEO_SIZE"))
        p4.add(lblMinVidSize)
        p4.add(Box.createHorizontalGlue())
        val cmbMinVidSize: JComboBox<*> = JComboBox<Any?>().apply {
            fixHeight(this)
            preferredSize = Dimension(150, preferredSize.height)
            maximumSize = Dimension(150, preferredSize.height)
        }
        p4.add(cmbMinVidSize)

        val lblBlockedHosts = JLabel(text("DESC_SITEEXCEPTIONS")).apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblBlockedHosts)

        val txtBlockedHosts = JTextArea().apply {
            rows = 3
            wrapStyleWord = true
            lineWrap = true
        }
        add(JScrollPane(txtBlockedHosts).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p5 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p5)
        p5.setLayout(BoxLayout(p5, BoxLayout.X_AXIS))

        val btnHostDef = JButton(text("DESC_DEF"))
        p5.add(btnHostDef)

        val chckbxNewCheckBox = JCheckBox("New check box").apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(chckbxNewCheckBox)
    }

    override fun getInsets(): Insets {
        return Insets(10, 10, 10, 10)
    }
}

class NetworkConfigPanel : JPanel() {
    private val txtProxyHost: JTextField
    private val txtProxyUser: JTextField
    private val txtProxyPass: JPasswordField?

    init {
        val gridBagLayout = GridBagLayout()
        gridBagLayout.columnWidths = intArrayOf(0, 0, 0, 0)
        gridBagLayout.rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        gridBagLayout.columnWeights = doubleArrayOf(0.0, 1.0, 1.0, Double.Companion.MIN_VALUE)
        gridBagLayout.rowWeights =
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.Companion.MIN_VALUE)
        setLayout(gridBagLayout)

        val lblTitle = JLabel(text("SETTINGS_NETWORK")).apply { font = font.deriveFont(16.0f) }
        val gbclblTitle = GridBagConstraints()
        gbclblTitle.anchor = GridBagConstraints.WEST
        gbclblTitle.ipady = 10
        gbclblTitle.gridwidth = 3
        gbclblTitle.insets = Insets(0, 0, 5, 0)
        gbclblTitle.gridx = 0
        gbclblTitle.gridy = 0
        add(lblTitle, gbclblTitle)

        val lblSplit = JLabel(text("MSG_MAX_SPLIT"))
        val gbclblSplit = GridBagConstraints()
        gbclblSplit.insets = Insets(0, 0, 5, 5)
        gbclblSplit.anchor = GridBagConstraints.WEST
        gbclblSplit.gridx = 0
        gbclblSplit.gridy = 1
        add(lblSplit, gbclblSplit)

        val cmbSplit: JComboBox<*> = JComboBox<Any?>()
        val gbcCmbsplit = GridBagConstraints()
        gbcCmbsplit.insets = Insets(0, 0, 5, 0)
        gbcCmbsplit.fill = GridBagConstraints.HORIZONTAL
        gbcCmbsplit.gridx = 2
        gbcCmbsplit.gridy = 1
        add(cmbSplit, gbcCmbsplit)

        val lblRetry = JLabel(text("MSG_MAX_RETRY"))
        val gbclblRetry = GridBagConstraints()
        gbclblRetry.anchor = GridBagConstraints.WEST
        gbclblRetry.insets = Insets(0, 0, 5, 5)
        gbclblRetry.gridx = 0
        gbclblRetry.gridy = 2
        add(lblRetry, gbclblRetry)

        val cmbRetry: JComboBox<*> = JComboBox<Any?>()
        val gbcCmbretry = GridBagConstraints()
        gbcCmbretry.insets = Insets(0, 0, 5, 0)
        gbcCmbretry.fill = GridBagConstraints.HORIZONTAL
        gbcCmbretry.gridx = 2
        gbcCmbretry.gridy = 2
        add(cmbRetry, gbcCmbretry)

        val chkProxy = JCheckBox(text("MSG_USE_PROXY"))
        val gbcChkproxy = GridBagConstraints()
        gbcChkproxy.anchor = GridBagConstraints.WEST
        gbcChkproxy.insets = Insets(0, 0, 5, 5)
        gbcChkproxy.gridx = 0
        gbcChkproxy.gridy = 4
        add(chkProxy, gbcChkproxy)

        val lblProxyHost = JLabel(text("MSG_PROXY_HOST"))
        val gbcchkProxy = GridBagConstraints()
        gbcchkProxy.anchor = GridBagConstraints.EAST
        gbcchkProxy.insets = Insets(0, 0, 5, 5)
        gbcchkProxy.gridx = 0
        gbcchkProxy.gridy = 5
        add(lblProxyHost, gbcchkProxy)

        txtProxyHost = JTextField()
        val gbctxtProxyHost = GridBagConstraints()
        gbctxtProxyHost.insets = Insets(0, 0, 5, 5)
        gbctxtProxyHost.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyHost.gridx = 1
        gbctxtProxyHost.gridy = 5
        add(txtProxyHost, gbctxtProxyHost)
        txtProxyHost.setColumns(10)

        val lblProxyPort = JLabel(text("MSG_PROXY_PORT"))
        val gbclblProxyPort = GridBagConstraints()
        gbclblProxyPort.anchor = GridBagConstraints.EAST
        gbclblProxyPort.insets = Insets(0, 0, 5, 5)
        gbclblProxyPort.gridx = 0
        gbclblProxyPort.gridy = 6
        add(lblProxyPort, gbclblProxyPort)

        val spProxyPort = JSpinner()
        val gbcspProxyPort = GridBagConstraints()
        gbcspProxyPort.anchor = GridBagConstraints.WEST
        gbcspProxyPort.insets = Insets(0, 0, 5, 5)
        gbcspProxyPort.gridx = 1
        gbcspProxyPort.gridy = 6
        add(spProxyPort, gbcspProxyPort)

        val lblProxyUser = JLabel(text("MSG_PROXY_USER"))
        val gbclblProxyUser = GridBagConstraints()
        gbclblProxyUser.anchor = GridBagConstraints.EAST
        gbclblProxyUser.insets = Insets(0, 0, 5, 5)
        gbclblProxyUser.gridx = 0
        gbclblProxyUser.gridy = 7
        add(lblProxyUser, gbclblProxyUser)

        txtProxyUser = JTextField()
        val gbctxtProxyUser = GridBagConstraints()
        gbctxtProxyUser.insets = Insets(0, 0, 5, 5)
        gbctxtProxyUser.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyUser.gridx = 1
        gbctxtProxyUser.gridy = 7
        add(txtProxyUser, gbctxtProxyUser)
        txtProxyUser.setColumns(10)

        val lblProxyPass = JLabel(text("MSG_PROXY_PASS"))
        val gbclblProxyPass = GridBagConstraints()
        gbclblProxyPass.anchor = GridBagConstraints.EAST
        gbclblProxyPass.insets = Insets(0, 0, 5, 5)
        gbclblProxyPass.gridx = 0
        gbclblProxyPass.gridy = 8
        add(lblProxyPass, gbclblProxyPass)

        txtProxyPass = JPasswordField()
        val gbctxtProxyPass = GridBagConstraints()
        gbctxtProxyPass.insets = Insets(0, 0, 5, 5)
        gbctxtProxyPass.fill = GridBagConstraints.HORIZONTAL
        gbctxtProxyPass.gridx = 1
        gbctxtProxyPass.gridy = 8
        add(txtProxyPass, gbctxtProxyPass)
    }

    override fun getInsets(): Insets {
        return Insets(15, 10, 10, 10)
    }
}

class AdvancedConfigPanel : JPanel() {
    private val txtCmd: JTextField
    private val txtVirusScan: JTextField
    private val txtArgs: JTextField

    init {

        val gridBagLayout = GridBagLayout()
        gridBagLayout.columnWidths = intArrayOf(0, 0, 0)
        gridBagLayout.rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        gridBagLayout.columnWeights = doubleArrayOf(1.0, 0.0, Double.Companion.MIN_VALUE)
        gridBagLayout.rowWeights =
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.Companion.MIN_VALUE)
        setLayout(gridBagLayout)

        val lblTitle = JLabel(text("MSG_ADV_TITLE")).apply { font = font.deriveFont(16.0f) }
        val gbcLbltitle = GridBagConstraints()
        gbcLbltitle.anchor = GridBagConstraints.WEST
        gbcLbltitle.insets = Insets(5, 0, 10, 5)
        gbcLbltitle.gridx = 0
        gbcLbltitle.gridy = 0
        add(lblTitle, gbcLbltitle)

        val chkHalt = JCheckBox(text("MSG_HALT"))
        val gbcChkhalt = GridBagConstraints()
        gbcChkhalt.anchor = GridBagConstraints.WEST
        gbcChkhalt.insets = Insets(0, 0, 5, 5)
        gbcChkhalt.gridx = 0
        gbcChkhalt.gridy = 1
        add(chkHalt, gbcChkhalt)

        val chkNoSleep = JCheckBox(text("MSG_AWAKE"))
        val gbcChknosleep = GridBagConstraints()
        gbcChknosleep.anchor = GridBagConstraints.WEST
        gbcChknosleep.insets = Insets(0, 0, 5, 5)
        gbcChknosleep.gridx = 0
        gbcChknosleep.gridy = 2
        add(chkNoSleep, gbcChknosleep)

        val chkRunCmd = JCheckBox(text("MSG_RUN_CMD"))
        val gbcChkruncmd = GridBagConstraints()
        gbcChkruncmd.anchor = GridBagConstraints.WEST
        gbcChkruncmd.insets = Insets(0, 0, 5, 5)
        gbcChkruncmd.gridx = 0
        gbcChkruncmd.gridy = 3
        add(chkRunCmd, gbcChkruncmd)

        txtCmd = JTextField()
        val gbcTxtcmd = GridBagConstraints()
        gbcTxtcmd.insets = Insets(0, 0, 5, 0)
        gbcTxtcmd.gridwidth = 3
        gbcTxtcmd.fill = GridBagConstraints.HORIZONTAL
        gbcTxtcmd.gridx = 0
        gbcTxtcmd.gridy = 4
        add(txtCmd, gbcTxtcmd)
        txtCmd.setColumns(10)

        val chkVirusScan = JCheckBox(text("MSG_SCAN"))
        val gbcChkvirusscan = GridBagConstraints()
        gbcChkvirusscan.insets = Insets(0, 0, 5, 5)
        gbcChkvirusscan.anchor = GridBagConstraints.WEST
        gbcChkvirusscan.gridx = 0
        gbcChkvirusscan.gridy = 5
        add(chkVirusScan, gbcChkvirusscan)

        val lblAVcmd = JLabel(text("MSG_AV_CMD"))
        val gbcLblAVcmd = GridBagConstraints()
        gbcLblAVcmd.anchor = GridBagConstraints.WEST
        gbcLblAVcmd.insets = Insets(0, 0, 5, 5)
        gbcLblAVcmd.gridx = 0
        gbcLblAVcmd.gridy = 6
        add(lblAVcmd, gbcLblAVcmd)

        txtVirusScan = JTextField()
        val gbcTxtvirusscan = GridBagConstraints()
        gbcTxtvirusscan.insets = Insets(0, 0, 5, 5)
        gbcTxtvirusscan.fill = GridBagConstraints.HORIZONTAL
        gbcTxtvirusscan.gridx = 0
        gbcTxtvirusscan.gridy = 6
        add(txtVirusScan, gbcTxtvirusscan)
        txtVirusScan.setColumns(10)

        val btnBrowse = JButton("...")
        val gbcBtnbrowse = GridBagConstraints()
        gbcBtnbrowse.insets = Insets(0, 0, 5, 0)
        gbcBtnbrowse.gridx = 1
        gbcBtnbrowse.gridy = 6
        add(btnBrowse, gbcBtnbrowse)

        val lblArgs = JLabel(text("MSG_ARGS"))
        val gbcLblargs = GridBagConstraints()
        gbcLblargs.anchor = GridBagConstraints.WEST
        gbcLblargs.insets = Insets(0, 0, 5, 5)
        gbcLblargs.gridx = 0
        gbcLblargs.gridy = 7
        add(lblArgs, gbcLblargs)

        txtArgs = JTextField()
        val gbcTxtargs = GridBagConstraints()
        gbcTxtargs.gridwidth = 2
        gbcTxtargs.insets = Insets(0, 0, 0, 5)
        gbcTxtargs.fill = GridBagConstraints.HORIZONTAL
        gbcTxtargs.gridx = 0
        gbcTxtargs.gridy = 8
        add(txtArgs, gbcTxtargs)
        txtArgs.setColumns(10)
    }

    override fun getInsets(): Insets {
        return Insets(15, 10, 10, 10)
    }
}

fun fixHeight(comp: JComponent) {
    val height = comp.preferredSize.height
    comp.maximumSize = Dimension(comp.maximumSize.width, height)
    comp.minimumSize = Dimension(comp.minimumSize.width, height)
}

fun padding(comp: JComponent, padding: Int, bottomPadding: Boolean = true, topPadding: Boolean = false) {
    comp.border = EmptyBorder(
        if (topPadding) padding else 0,
        0,
        if (bottomPadding) padding else 0,
        0,
    )
}
