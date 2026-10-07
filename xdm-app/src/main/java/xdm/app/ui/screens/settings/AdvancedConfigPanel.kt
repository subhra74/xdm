package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.OS
import xdm.app.utils.AutoStart
import xdm.app.utils.JitOverride
import xdm.app.utils.WaylandToolkit
import xdm.app.utils.chooseFile
import xdm.app.utils.detectOS
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
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
    private val tglFullJit = SettingsToggle()
    // Linux only: Windows builds have no per-user .cfg (the MSI may put the heap line in the installed one,
    // PACKAGING.md §5.7), and the toggle is hidden on macOS too.
    private val showFullJit = detectOS() == OS.Linux
    private val tglWayland = SettingsToggle()
    private val tglSkipDupManifests = SettingsToggle()

    private val tglRunCmd = SettingsToggle()
    private val txtCmd = rounded(JTextField()).apply {
        putClientProperty(placeholder, I8N.text("MSG_CUSTOM_CMD"))
        columns = 10
    }

    private val tglMarkDownloads = SettingsToggle()
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
                *listOfNotNull(
                    settingsRow(I8N.text("MSG_FULL_JIT"), I8N.text("MSG_FULL_JIT_SUB"), tglFullJit).takeIf { showFullJit },
                    settingsRow(I8N.text("MSG_WAYLAND"), I8N.text("MSG_WAYLAND_SUB"), tglWayland),
                ).toTypedArray(),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ADV_VIDEO"),
                settingsRow(
                    I8N.text("MSG_SKIP_DUP_MANIFEST"),
                    I8N.text("MSG_SKIP_DUP_MANIFEST_SUB"),
                    tglSkipDupManifests
                ),
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
                settingsRow(I8N.text("MSG_MARK_DOWNLOADS"), I8N.text("MSG_MARK_DOWNLOADS_SUB"), tglMarkDownloads),
                settingsRow(I8N.text("MSG_SCAN"), I8N.text("MSG_SCAN_SUB"), tglVirusScan),
                settingsFullRow(
                    null, null,
                    Box.createHorizontalBox().apply {
                        alignmentX = LEFT_ALIGNMENT
                        txtVirusScan.maximumSize = Dimension(Int.MAX_VALUE, txtVirusScan.preferredSize.height)
                        add(txtVirusScan)
                        add(Box.createRigidArea(scaledSize(10, 0)))
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
        // The OS entry, not the config, is the truth: it can be removed behind XDM's back.
        tglRunOnStartup.isSelected = AutoStart.isEnabled()
        // Like autostart, the file on disk is the setting. Dev runs and tar.gz builds show it off and locked.
        tglFullJit.isSelected = JitOverride.isEnabled()
        tglFullJit.isEnabled = JitOverride.isConfigurable
        // Only a Linux Wayland session on the JetBrains Runtime has a choice; elsewhere it shows off and locked.
        tglWayland.isSelected = WaylandToolkit.isSupported && config.nativeWayland
        tglWayland.isEnabled = WaylandToolkit.isSupported
        tglSkipDupManifests.isSelected = config.skipDuplicateManifests
        tglRunCmd.isSelected = config.runCommand
        txtCmd.text = config.customCommand
        tglMarkDownloads.isSelected = config.markDownloadedFiles
        tglVirusScan.isSelected = config.runVirusScan
        txtVirusScan.text = config.virusScannerPath
        txtArgs.text = config.virusScannerArgs
        updateEnabledState()
    }

    fun save() {
        val config = AppContext.config
        config.haltAfterDownload = tglHalt.isSelected
        config.keepAwake = tglNoSleep.isSelected
        if (AutoStart.isEnabled() != tglRunOnStartup.isSelected) {
            AutoStart.setEnabled(tglRunOnStartup.isSelected)
        }
        // Record what actually took effect - enabling can fail.
        config.runOnStartup = AutoStart.isEnabled()
        if (showFullJit && JitOverride.isConfigurable && JitOverride.isEnabled() != tglFullJit.isSelected) {
            JitOverride.setEnabled(tglFullJit.isSelected)
        }
        if (WaylandToolkit.isSupported) {
            config.nativeWayland = tglWayland.isSelected
        }
        config.skipDuplicateManifests = tglSkipDupManifests.isSelected
        config.runCommand = tglRunCmd.isSelected
        config.customCommand = txtCmd.text
        config.markDownloadedFiles = tglMarkDownloads.isSelected
        config.runVirusScan = tglVirusScan.isSelected
        config.virusScannerPath = txtVirusScan.text
        config.virusScannerArgs = txtArgs.text
    }

    override fun getInsets(): Insets = scaledInsets(18, 24, 24, 24)
}
