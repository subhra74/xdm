package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.AutoStart
import xdm.app.utils.chooseFile
import xdm.app.utils.createSVGIcon
import xdm.app.utils.fixHeight
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class AdvancedConfigPanel : SettingsPanel() {
    private val key = "JTextField.placeholderText"
    private val txtCmd = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_CUSTOM_CMD"))
        columns = 10
        fixHeight(this)
    }
    private val txtVirusScan = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_AV_CMD"))
        columns = 10
        fixHeight(this)
    }
    private val txtArgs = JTextField().apply {
        putClientProperty(key, I8N.text("MSG_ARGS"))
        columns = 10
        fixHeight(this)
    }
    private val chkHalt = JCheckBox(I8N.text("MSG_HALT"))
    private val chkNoSleep = JCheckBox(I8N.text("MSG_AWAKE"))
    private val chkRunOnStartup = JCheckBox(I8N.text("MSG_AUTOSTART"))
    private val chkRunCmd = JCheckBox(I8N.text("MSG_RUN_CMD"))
    private val chkVirusScan = JCheckBox(I8N.text("MSG_SCAN"))
    private val btnBrowse = JButton(
        I8N.text("SETTINGS_FOLDER_CHANGE"),
        createSVGIcon("folder-6-line.svg", 16, settingsAccentColor())
    ).apply {
        iconTextGap = 6
        fixHeight(this)
    }

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        add(settingsTitle(I8N.text("MSG_ADV_TITLE")))

        // General
        add(
            settingsCard(
                "settings-4-line.svg", I8N.text("SETTINGS_SEC_ADV_GENERAL"),
                settingsLeftAligned(chkHalt),
                settingsLeftAligned(chkNoSleep),
                settingsLeftAligned(chkRunOnStartup),
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Custom command
        add(
            settingsCard(
                "file-text-line.svg", I8N.text("SETTINGS_SEC_COMMAND"),
                settingsLeftAligned(chkRunCmd),
                fullWidth(txtCmd),
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Antivirus
        val scannerRow = Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(txtVirusScan)
            add(Box.createRigidArea(Dimension(8, 0)))
            add(btnBrowse)
        }
        add(
            settingsCard(
                "file-shield-line.svg", I8N.text("SETTINGS_SEC_ANTIVIRUS"),
                settingsLeftAligned(chkVirusScan),
                scannerRow,
                fullWidth(txtArgs),
            )
        )

        add(Box.createVerticalGlue())

        btnBrowse.addActionListener { chooseScanner() }
        chkRunCmd.addActionListener { updateEnabledState() }
        chkVirusScan.addActionListener { updateEnabledState() }
    }

    private fun fullWidth(comp: JComponent): JComponent =
        Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(comp)
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
        return Insets(10, 12, 12, 12)
    }
}
