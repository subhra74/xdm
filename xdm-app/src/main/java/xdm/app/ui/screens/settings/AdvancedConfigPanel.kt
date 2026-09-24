package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.AutoStart
import xdm.app.utils.chooseFile
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextField

/** The things most people never touch: system behaviour, and programs run after a download. */
class AdvancedConfigPanel : SettingsPanel() {
    private val placeholder = "JTextField.placeholderText"

    private val tglHalt = SettingsToggle()
    private val tglNoSleep = SettingsToggle()
    private val tglRunOnStartup = SettingsToggle()

    private val tglRunCmd = SettingsToggle()
    private val txtCmd = rounded(JTextField()).apply {
        putClientProperty(placeholder, I8N.text("MSG_CUSTOM_CMD"))
        columns = 10
    }

    private val tglVirusScan = SettingsToggle()
    private val txtVirusScan = rounded(JTextField()).apply {
        putClientProperty(placeholder, I8N.text("MSG_AV_CMD"))
        columns = 10
    }
    private val txtArgs = rounded(JTextField()).apply {
        putClientProperty(placeholder, I8N.text("MSG_ARGS"))
        columns = 10
    }
    private val btnBrowse: JButton = settingsButton(I8N.text("SETTINGS_FOLDER_CHANGE")) { chooseScanner() }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("MSG_ADV_TITLE")))

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ADV_GENERAL"),
                settingsRow(I8N.text("MSG_AUTOSTART"), I8N.text("MSG_AUTOSTART_SUB"), tglRunOnStartup),
                settingsRow(I8N.text("MSG_AWAKE"), I8N.text("MSG_AWAKE_SUB"), tglNoSleep),
                settingsRow(I8N.text("MSG_HALT"), I8N.text("MSG_HALT_SUB"), tglHalt),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_COMMAND"),
                settingsRow(I8N.text("MSG_RUN_CMD"), I8N.text("MSG_RUN_CMD_SUB"), tglRunCmd),
                settingsFullRow(null, null, fullWidth(txtCmd)),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ANTIVIRUS"),
                settingsRow(I8N.text("MSG_SCAN"), I8N.text("MSG_SCAN_SUB"), tglVirusScan),
                settingsFullRow(
                    null, null,
                    Box.createHorizontalBox().apply {
                        alignmentX = LEFT_ALIGNMENT
                        txtVirusScan.maximumSize = Dimension(Int.MAX_VALUE, txtVirusScan.preferredSize.height)
                        add(txtVirusScan)
                        add(Box.createRigidArea(Dimension(10, 0)))
                        add(btnBrowse)
                    }
                ),
                settingsFullRow(null, null, fullWidth(txtArgs)),
            )
        )

        add(Box.createVerticalGlue())

        tglRunCmd.addActionListener { updateEnabledState() }
        tglVirusScan.addActionListener { updateEnabledState() }
    }

    private fun fullWidth(comp: JComponent): JComponent =
        Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            comp.maximumSize = Dimension(Int.MAX_VALUE, comp.preferredSize.height)
            add(comp)
        }

    private fun chooseScanner() {
        chooseFile(this, directoriesOnly = false)?.let {
            txtVirusScan.text = it.absolutePath
        }
    }

    private fun updateEnabledState() {
        txtCmd.isEnabled = tglRunCmd.isSelected
        txtVirusScan.isEnabled = tglVirusScan.isSelected
        txtArgs.isEnabled = tglVirusScan.isSelected
        btnBrowse.isEnabled = tglVirusScan.isSelected
    }

    fun load() {
        val config = AppContext.config
        tglHalt.isSelected = config.haltAfterDownload
        tglNoSleep.isSelected = config.keepAwake
        tglRunOnStartup.isSelected = config.runOnStartup
        tglRunCmd.isSelected = config.runCommand
        txtCmd.text = config.customCommand
        tglVirusScan.isSelected = config.runVirusScan
        txtVirusScan.text = config.virusScannerPath
        txtArgs.text = config.virusScannerArgs
        updateEnabledState()
    }

    fun save() {
        val config = AppContext.config
        config.haltAfterDownload = tglHalt.isSelected
        config.keepAwake = tglNoSleep.isSelected
        if (config.runOnStartup != tglRunOnStartup.isSelected) {
            config.runOnStartup = tglRunOnStartup.isSelected
            AutoStart.setEnabled(tglRunOnStartup.isSelected)
        }
        config.runCommand = tglRunCmd.isSelected
        config.customCommand = txtCmd.text
        config.runVirusScan = tglVirusScan.isSelected
        config.virusScannerPath = txtVirusScan.text
        config.virusScannerArgs = txtArgs.text
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)
}
