package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.AutoStart
import xdm.app.utils.chooseFile
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class AdvancedConfigPanel : JPanel() {
    private val key = "JTextField.placeholderText"
    private val txtCmd = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_CUSTOM_CMD"))
        columns = 10
    }
    private val txtVirusScan = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_AV_CMD"))
        columns = 10
    }
    private val txtArgs = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_ARGS"))
        columns = 10
    }
    private val chkHalt = JCheckBox(I8N.text("MSG_HALT"))
    private val chkNoSleep = JCheckBox(I8N.text("MSG_AWAKE"))
    private val chkRunOnStartup = JCheckBox(I8N.text("MSG_AUTOSTART"))
    private val chkRunCmd = JCheckBox(I8N.text("MSG_RUN_CMD"))
    private val chkVirusScan = JCheckBox(I8N.text("MSG_SCAN"))
    private val btnBrowse = JButton("...")

    init {

        val gridBagLayout = GridBagLayout()
        gridBagLayout.columnWidths = intArrayOf(0, 0, 0)
        gridBagLayout.rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        gridBagLayout.columnWeights = doubleArrayOf(1.0, 0.0, Double.Companion.MIN_VALUE)
        gridBagLayout.rowWeights =
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, Double.Companion.MIN_VALUE)
        setLayout(gridBagLayout)

        val lblTitle = JLabel(I8N.text("MSG_ADV_TITLE")).apply { font = font.deriveFont(16.0f) }
        val gbcLbltitle = GridBagConstraints()
        gbcLbltitle.anchor = GridBagConstraints.WEST
        gbcLbltitle.insets = Insets(5, 0, 10, 5)
        gbcLbltitle.gridx = 0
        gbcLbltitle.gridy = 0
        add(lblTitle, gbcLbltitle)

        val gbcChkhalt = GridBagConstraints()
        gbcChkhalt.anchor = GridBagConstraints.WEST
        gbcChkhalt.insets = Insets(0, 0, 5, 5)
        gbcChkhalt.gridx = 0
        gbcChkhalt.gridy = 1
        add(chkHalt, gbcChkhalt)

        val gbcChknosleep = GridBagConstraints()
        gbcChknosleep.anchor = GridBagConstraints.WEST
        gbcChknosleep.insets = Insets(0, 0, 5, 5)
        gbcChknosleep.gridx = 0
        gbcChknosleep.gridy = 2
        add(chkNoSleep, gbcChknosleep)

        val gbcChkstartup = GridBagConstraints()
        gbcChkstartup.anchor = GridBagConstraints.WEST
        gbcChkstartup.insets = Insets(0, 0, 5, 5)
        gbcChkstartup.gridx = 0
        gbcChkstartup.gridy = 3
        add(chkRunOnStartup, gbcChkstartup)

        val gbcChkruncmd = GridBagConstraints()
        gbcChkruncmd.anchor = GridBagConstraints.WEST
        gbcChkruncmd.insets = Insets(0, 0, 5, 5)
        gbcChkruncmd.gridx = 0
        gbcChkruncmd.gridy = 4
        add(chkRunCmd, gbcChkruncmd)

        val gbcTxtcmd = GridBagConstraints()
        gbcTxtcmd.insets = Insets(0, 0, 5, 0)
        gbcTxtcmd.gridwidth = 3
        gbcTxtcmd.fill = GridBagConstraints.HORIZONTAL
        gbcTxtcmd.gridx = 0
        gbcTxtcmd.gridy = 5
        add(txtCmd, gbcTxtcmd)

        val gbcChkvirusscan = GridBagConstraints()
        gbcChkvirusscan.insets = Insets(0, 0, 5, 5)
        gbcChkvirusscan.anchor = GridBagConstraints.WEST
        gbcChkvirusscan.gridx = 0
        gbcChkvirusscan.gridy = 6
        add(chkVirusScan, gbcChkvirusscan)

        val gbcTxtvirusscan = GridBagConstraints()
        gbcTxtvirusscan.insets = Insets(0, 0, 5, 5)
        gbcTxtvirusscan.fill = GridBagConstraints.HORIZONTAL
        gbcTxtvirusscan.gridx = 0
        gbcTxtvirusscan.gridy = 7
        add(txtVirusScan, gbcTxtvirusscan)

        val gbcBtnbrowse = GridBagConstraints()
        gbcBtnbrowse.insets = Insets(0, 0, 5, 0)
        gbcBtnbrowse.gridx = 1
        gbcBtnbrowse.gridy = 7
        add(btnBrowse, gbcBtnbrowse)

        val gbcTxtargs = GridBagConstraints()
        gbcTxtargs.gridwidth = 2
        gbcTxtargs.insets = Insets(0, 0, 0, 5)
        gbcTxtargs.fill = GridBagConstraints.HORIZONTAL
        gbcTxtargs.gridx = 0
        gbcTxtargs.gridy = 8
        add(txtArgs, gbcTxtargs)

        btnBrowse.addActionListener { chooseScanner() }

        chkRunCmd.addActionListener { updateEnabledState() }
        chkVirusScan.addActionListener { updateEnabledState() }
    }

    private fun chooseScanner() {
        chooseFile(this, directoriesOnly = false)?.let {
            txtVirusScan.text = it.absolutePath
        }
    }

    private fun updateEnabledState() {
        txtCmd.isEnabled = chkRunCmd.isSelected
        txtVirusScan.isEnabled = chkVirusScan.isSelected
        txtArgs.isEnabled = chkVirusScan.isSelected
        btnBrowse.isEnabled = chkVirusScan.isSelected
    }

    fun load() {
        val config = AppContext.config
        chkHalt.isSelected = config.haltAfterDownload
        chkNoSleep.isSelected = config.keepAwake
        chkRunOnStartup.isSelected = config.runOnStartup
        chkRunCmd.isSelected = config.runCommand
        txtCmd.text = config.customCommand
        chkVirusScan.isSelected = config.runVirusScan
        txtVirusScan.text = config.virusScannerPath
        txtArgs.text = config.virusScannerArgs
        updateEnabledState()
    }

    fun save() {
        val config = AppContext.config
        config.haltAfterDownload = chkHalt.isSelected
        config.keepAwake = chkNoSleep.isSelected
        if (config.runOnStartup != chkRunOnStartup.isSelected) {
            config.runOnStartup = chkRunOnStartup.isSelected
            AutoStart.setEnabled(chkRunOnStartup.isSelected)
        }
        config.runCommand = chkRunCmd.isSelected
        config.customCommand = txtCmd.text
        config.runVirusScan = chkVirusScan.isSelected
        config.virusScannerPath = txtVirusScan.text
        config.virusScannerArgs = txtArgs.text
    }

    override fun getInsets(): Insets {
        return Insets(15, 10, 10, 10)
    }
}
