package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.torrent.TorrentClients
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.manager.EngineConfig
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * BitTorrent: the listening port and how peers are found, encryption, torrent speed limits (their own, apart
 * from the HTTP limit: torrents also upload) and seeding after a torrent finishes. Applied to a running session
 * when saved; the session itself starts only when a torrent runs.
 */
class TorrentPanel : SettingsPanel() {
    private val tglFixedPort = SettingsToggle()
    private val spnPort = spinner(SpinnerNumberModel(51413, 1024, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }
    private val tglPortMapping = SettingsToggle()
    private val tglDht = SettingsToggle()
    private val tglLsd = SettingsToggle()
    private val tglPex = SettingsToggle()
    private val cmbEncryption = rounded(JComboBox(arrayOf(
        I8N.text("BT_ENC_PREFER"), I8N.text("BT_ENC_REQUIRE"), I8N.text("BT_ENC_OFF"),
    ))).apply {
        preferredSize = Dimension(190.px, preferredSize.height)
        maximumSize = preferredSize
    }
    private val clients = TorrentClients.all(xdmVersion())
    private val cmbClient = rounded(JComboBox(clients.map { it.label }.toTypedArray())).apply {
        preferredSize = Dimension(190.px, preferredSize.height)
        maximumSize = preferredSize
    }
    private val tglKeepSession = SettingsToggle()
    private val tglAddTrackers = SettingsToggle()
    private val txtTrackers = javax.swing.JTextArea().apply {
        rows = 5
        border = xdm.app.utils.ScaledEmptyBorder(7, 9, 7, 9)
    }
    private val tglDownLimit = SettingsToggle()
    private val spnDownLimit = spinner(SpinnerNumberModel(1000, 1, 10_000_000, 10))
    private val tglUpLimit = SettingsToggle()
    private val spnUpLimit = spinner(SpinnerNumberModel(200, 1, 10_000_000, 10))
    private val tglSeed = SettingsToggle()
    private val spnRatio = spinner(SpinnerNumberModel(1.0, 0.0, 100.0, 0.1)).apply {
        editor = JSpinner.NumberEditor(this, "0.0")
    }
    private val spnMinutes = spinner(SpinnerNumberModel(0, 0, 100_000, 10))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(settingsTitle(I8N.text("SETTINGS_BITTORRENT")))
        add(
            settingsSection(
                I8N.text("BT_SEC_SESSION"),
                settingsRow(I8N.text("BT_KEEP_SESSION"), I8N.text("BT_KEEP_SESSION_SUB"), tglKeepSession),
            )
        )
        add(settingsGap())
        add(
            settingsSection(
                I8N.text("BT_SEC_CONNECTION"),
                settingsRow(I8N.text("BT_FIXED_PORT"), I8N.text("BT_FIXED_PORT_SUB"), tglFixedPort),
                settingsRow(I8N.text("BT_PORT"), null, spnPort),
                settingsRow(I8N.text("BT_PORT_MAPPING"), I8N.text("BT_PORT_MAPPING_SUB"), tglPortMapping),
                settingsRow(I8N.text("BT_DHT"), I8N.text("BT_DHT_SUB"), tglDht),
                settingsRow(I8N.text("BT_LSD"), I8N.text("BT_LSD_SUB"), tglLsd),
                settingsRow(I8N.text("BT_PEX"), I8N.text("BT_PEX_SUB"), tglPex),
                settingsRow(I8N.text("BT_ENCRYPTION"), I8N.text("BT_ENCRYPTION_SUB"), cmbEncryption),
                settingsRow(I8N.text("BT_CLIENT"), I8N.text("BT_CLIENT_SUB"), cmbClient),
            )
        )
        add(settingsGap())
        add(
            settingsSection(
                I8N.text("BT_SEC_TRACKERS"),
                settingsRow(I8N.text("BT_ADD_TRACKERS"), I8N.text("BT_ADD_TRACKERS_SUB"), tglAddTrackers),
                settingsFullRow(null, I8N.text("BT_TRACKER_LIST_SUB"), rounded(javax.swing.JScrollPane(txtTrackers)).apply {
                    alignmentX = LEFT_ALIGNMENT
                    preferredSize = Dimension(preferredSize.width, 110.px)
                    maximumSize = Dimension(Int.MAX_VALUE, 110.px)
                }),
            )
        )
        add(settingsGap())
        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_SPEED"),
                settingsRow(I8N.text("BT_DOWN_LIMIT"), I8N.text("BT_LIMIT_SUB"), tglDownLimit),
                settingsRow(I8N.text("MSG_SPEED_LIMIT_VALUE"), null, withSuffix(spnDownLimit, I8N.text("MSG_SPEED_LIMIT_UNIT"))),
                settingsRow(I8N.text("BT_UP_LIMIT"), null, tglUpLimit),
                settingsRow(I8N.text("MSG_SPEED_LIMIT_VALUE"), null, withSuffix(spnUpLimit, I8N.text("MSG_SPEED_LIMIT_UNIT"))),
            )
        )
        add(settingsGap())
        add(
            settingsSection(
                I8N.text("BT_SEC_SEEDING"),
                settingsRow(I8N.text("BT_SEED"), I8N.text("BT_SEED_SUB"), tglSeed),
                settingsRow(I8N.text("BT_RATIO"), I8N.text("BT_RATIO_SUB"), spnRatio),
                settingsRow(I8N.text("BT_MINUTES"), I8N.text("BT_MINUTES_SUB"), withSuffix(spnMinutes, I8N.text("BT_MINUTES_UNIT"))),
            )
        )
        add(Box.createVerticalGlue())
        listOf(tglFixedPort, tglDownLimit, tglUpLimit, tglSeed, tglAddTrackers).forEach { it.addActionListener { updateEnabledState() } }
    }

    private fun spinner(model: SpinnerNumberModel) = rounded(JSpinner(model)).apply {
        preferredSize = Dimension(110.px, preferredSize.height)
        maximumSize = preferredSize
    }

    private fun withSuffix(control: JComponent, suffix: String): JComponent =
        Box.createHorizontalBox().apply {
            add(control)
            add(Box.createRigidArea(scaledSize(8, 0)))
            add(JLabel(suffix).apply { foreground = settingsMutedColor() })
        }

    private fun updateEnabledState() {
        spnPort.isEnabled = tglFixedPort.isSelected
        spnDownLimit.isEnabled = tglDownLimit.isSelected
        spnUpLimit.isEnabled = tglUpLimit.isSelected
        spnRatio.isEnabled = tglSeed.isSelected
        spnMinutes.isEnabled = tglSeed.isSelected
        txtTrackers.isEnabled = tglAddTrackers.isSelected
    }

    fun load() {
        val c = AppContext.config
        tglFixedPort.isSelected = c.torrentPort > 0
        if (c.torrentPort >= 1024) spnPort.value = c.torrentPort
        tglPortMapping.isSelected = c.torrentPortMapping
        tglDht.isSelected = c.torrentDht
        tglLsd.isSelected = c.torrentLsd
        tglPex.isSelected = c.torrentPex
        cmbEncryption.selectedIndex = c.torrentEncryption.coerceIn(0, 2)
        cmbClient.selectedIndex = clients.indexOfFirst { it.id == c.torrentClient }.coerceAtLeast(0)
        tglDownLimit.isSelected = c.torrentDownloadLimit > 0
        if (c.torrentDownloadLimit > 0) spnDownLimit.value = c.torrentDownloadLimit
        tglUpLimit.isSelected = c.torrentUploadLimit > 0
        if (c.torrentUploadLimit > 0) spnUpLimit.value = c.torrentUploadLimit
        tglKeepSession.isSelected = c.torrentKeepSession
        tglAddTrackers.isSelected = c.torrentAddTrackers
        txtTrackers.text = c.torrentTrackerList
        tglSeed.isSelected = c.torrentSeedAfterFinish
        spnRatio.value = c.torrentSeedRatioLimit.toDouble()
        spnMinutes.value = c.torrentSeedTimeLimitMinutes
        updateEnabledState()
    }

    fun save() {
        val c = AppContext.config
        c.torrentPort = if (tglFixedPort.isSelected) spnPort.value as Int else 0
        c.torrentPortMapping = tglPortMapping.isSelected
        c.torrentDht = tglDht.isSelected
        c.torrentLsd = tglLsd.isSelected
        c.torrentPex = tglPex.isSelected
        c.torrentEncryption = cmbEncryption.selectedIndex.takeIf { it >= 0 } ?: EngineConfig.TORRENT_ENCRYPTION_PREFER
        c.torrentClient = clients.getOrNull(cmbClient.selectedIndex)?.id ?: TorrentClients.DEFAULT
        c.torrentDownloadLimit = if (tglDownLimit.isSelected) spnDownLimit.value as Int else 0
        c.torrentUploadLimit = if (tglUpLimit.isSelected) spnUpLimit.value as Int else 0
        c.torrentSeedAfterFinish = tglSeed.isSelected
        c.torrentSeedRatioLimit = (spnRatio.value as Number).toFloat()
        c.torrentSeedTimeLimitMinutes = spnMinutes.value as Int
        c.torrentKeepSession = tglKeepSession.isSelected
        c.torrentAddTrackers = tglAddTrackers.isSelected
        c.torrentTrackerList = txtTrackers.text.trim()
        AppContext.downloader.applyTorrentSettings()
        // Kept running from now on; turned off, the idle close takes it down 5 minutes after the last torrent.
        if (c.torrentKeepSession) AppContext.downloader.torrents.warmUp()
    }

    /** The XDM version shown in the client list (the same one the identity reports). */
    private fun xdmVersion(): String =
        runCatching { xdm.app.update.UpdateChecker.loadConfig()?.currentVersion }.getOrNull()?.removePrefix("v") ?: "9.0.0"

    override fun getInsets(): Insets = scaledInsets(18, 24, 24, 24)
}
