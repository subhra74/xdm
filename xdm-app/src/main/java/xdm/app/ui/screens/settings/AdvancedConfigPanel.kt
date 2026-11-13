package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.AutoStart
import xdm.app.OS
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
    private val tglWayland = SettingsToggle()
    private val tglSwingChooser = SettingsToggle()
    private val tglForceIpv4 = SettingsToggle()
    private val tglSkipDupManifests = SettingsToggle()
    private val tglFfmpeg = SettingsToggle()
    private val txtFfmpeg = rounded(JTextField()).apply {
        putClientProperty(placeholder, I8N.text("MSG_FFMPEG_PATH"))
        columns = 10
    }
    private val btnFfmpeg: JButton = settingsButton(I8N.text("SETTINGS_FOLDER_CHANGE")) { chooseFfmpeg() }

    private val commandRules = CommandRuleSection()

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
                settingsRow(I8N.text("MSG_WAYLAND"), I8N.text("MSG_WAYLAND_SUB"), tglWayland),
                settingsRow(I8N.text("MSG_SWING_CHOOSER"), I8N.text("MSG_SWING_CHOOSER_SUB"), tglSwingChooser),
                settingsRow(I8N.text("MSG_FORCE_IPV4"), I8N.text("MSG_FORCE_IPV4_SUB"), tglForceIpv4),
                settingsRow(I8N.text("MSG_LOGS"), I8N.text("MSG_LOGS_SUB"), settingsButton(I8N.text("MSG_LOGS_OPEN")) { openLogs() }),
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
                settingsRow(I8N.text("MSG_USE_FFMPEG"), I8N.text("MSG_USE_FFMPEG_SUB"), tglFfmpeg),
                settingsFullRow(null, null, pathRow(txtFfmpeg, btnFfmpeg)),
            )
        )
        add(settingsGap())

        add(commandRules.component)
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ANTIVIRUS"),
                settingsRow(I8N.text("MSG_MARK_DOWNLOADS"), I8N.text("MSG_MARK_DOWNLOADS_SUB"), tglMarkDownloads),
                settingsRow(I8N.text("MSG_SCAN"), I8N.text("MSG_SCAN_SUB"), tglVirusScan),
                settingsFullRow(null, null, pathRow(txtVirusScan, btnBrowse)),
                settingsFullRow(null, null, fullWidth(txtArgs)),
            )
        )

        add(Box.createVerticalGlue())

        tglVirusScan.addActionListener { updateEnabledState() }
        tglFfmpeg.addActionListener { updateEnabledState() }
    }

    /** A path field and its Browse button on one line. */
    private fun pathRow(field: JTextField, browse: JButton): JComponent =
        Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            field.maximumSize = Dimension(Int.MAX_VALUE, field.preferredSize.height)
            add(field)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(browse)
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

    private fun chooseFfmpeg() {
        chooseFile(this, directoriesOnly = false)?.let {
            txtFfmpeg.text = it.absolutePath
        }
    }

    private fun updateEnabledState() {
        txtFfmpeg.isEnabled = tglFfmpeg.isSelected
        btnFfmpeg.isEnabled = tglFfmpeg.isSelected
        txtVirusScan.isEnabled = tglVirusScan.isSelected
        txtArgs.isEnabled = tglVirusScan.isSelected
        btnBrowse.isEnabled = tglVirusScan.isSelected
    }

    /** Opens the folder with this and the last few launches' logs, for a bug report. */
    private fun openLogs() {
        val dir = xdm.core.util.Logger.logFolder ?: return
        runCatching { xdm.app.utils.openFolderExternal(null, dir.absolutePath) }
            .onFailure { xdm.core.util.Logger.error("Could not open the logs folder $dir", it) }
    }

    fun load() {
        val config = AppContext.config
        tglHalt.isSelected = config.haltAfterDownload
        tglNoSleep.isSelected = config.keepAwake
        // The OS entry, not the config, is the truth: it can be removed behind XDM's back.
        tglRunOnStartup.isSelected = AutoStart.isEnabled()
        // Only a Linux Wayland session on the JetBrains Runtime has a choice; elsewhere it shows off and locked.
        tglWayland.isSelected = WaylandToolkit.isSupported && config.nativeWayland
        tglWayland.isEnabled = WaylandToolkit.isSupported
        // Only Linux and the BSDs have a portal dialog to opt out of; elsewhere it shows off and locked.
        val linux = detectOS().isXdg
        tglSwingChooser.isSelected = linux && config.swingFileChooser
        tglSwingChooser.isEnabled = linux
        tglForceIpv4.isSelected = config.forceIpv4
        tglSkipDupManifests.isSelected = config.skipDuplicateManifests
        tglFfmpeg.isSelected = config.useFfmpegMuxer
        txtFfmpeg.text = config.ffmpegPath
        commandRules.load()
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
        if (WaylandToolkit.isSupported) {
            config.nativeWayland = tglWayland.isSelected
        }
        if (detectOS().isXdg) {
            config.swingFileChooser = tglSwingChooser.isSelected
        }
        config.forceIpv4 = tglForceIpv4.isSelected
        config.skipDuplicateManifests = tglSkipDupManifests.isSelected
        config.ffmpegPath = txtFfmpeg.text.trim()
        // On only with a path: ffmpeg is never looked up on its own.
        config.useFfmpegMuxer = tglFfmpeg.isSelected && config.ffmpegPath.isNotEmpty()
        commandRules.save()
        config.markDownloadedFiles = tglMarkDownloads.isSelected
        config.runVirusScan = tglVirusScan.isSelected
        config.virusScannerPath = txtVirusScan.text
        config.virusScannerArgs = txtArgs.text
    }

    override fun getInsets(): Insets = scaledInsets(18, 24, 24, 24)
}
