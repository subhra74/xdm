package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
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
 * How downloads are run: how fast, how many at a time, and how hard the engine tries. These
 * used to be split between General and Network, which meant two pages to answer one question.
 */
class DownloadsPanel : SettingsPanel() {
    private val tglSpeedLimiter = SettingsToggle()
    private val spnSpeedLimiter = rounded(JSpinner(SpinnerNumberModel(100, 0, 10000000, 1))).apply {
        preferredSize = Dimension(110, preferredSize.height)
        maximumSize = preferredSize
    }
    private val cmbMaxConn = numberCombo(listOf(1, 2, 4, 8, 16, 32))
    private val cmbSplit = numberCombo(listOf(1, 2, 4, 8, 16, 32, 64))
    private val cmbRetry = numberCombo((1..99).toList())

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_DOWNLOADS")))

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_SPEED"),
                settingsRow(I8N.text("MSG_SPEED_LIMIT"), I8N.text("MSG_SPEED_LIMIT_SUB"), tglSpeedLimiter),
                settingsRow(
                    I8N.text("MSG_SPEED_LIMIT_VALUE"),
                    null,
                    withSuffix(spnSpeedLimiter, I8N.text("MSG_SPEED_LIMIT_UNIT"))
                ),
                settingsRow(I8N.text("MSG_MAX_DOWNLOAD"), I8N.text("MSG_MAX_DOWNLOAD_SUB"), cmbMaxConn),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_CONNECTION"),
                settingsRow(I8N.text("MSG_MAX_SPLIT"), I8N.text("MSG_MAX_SPLIT_SUB"), cmbSplit),
                settingsRow(I8N.text("MSG_MAX_RETRY"), I8N.text("MSG_MAX_RETRY_SUB"), cmbRetry),
            )
        )

        add(Box.createVerticalGlue())

        tglSpeedLimiter.addActionListener { updateEnabledState() }
    }

    private fun numberCombo(values: List<Int>): JComboBox<Int> =
        rounded(JComboBox<Int>()).apply {
            values.forEach { addItem(it) }
            preferredSize = Dimension(110, preferredSize.height)
            maximumSize = preferredSize
        }

    /** A control with a trailing unit label ("KB/s", "seconds") instead of one baked into the title. */
    private fun withSuffix(control: JComponent, suffix: String): JComponent =
        Box.createHorizontalBox().apply {
            add(control)
            add(Box.createRigidArea(Dimension(8, 0)))
            add(JLabel(suffix).apply { foreground = settingsMutedColor() })
        }

    private fun updateEnabledState() {
        spnSpeedLimiter.isEnabled = tglSpeedLimiter.isSelected
    }

    fun load() {
        val config = AppContext.config
        tglSpeedLimiter.isSelected = config.speedLimiterEnabled
        spnSpeedLimiter.value = config.speedLimit
        cmbMaxConn.selectedItem = config.maxParallelDownloads
        cmbSplit.selectedItem = config.maxSegments
        cmbRetry.selectedItem = config.maxRetries
        updateEnabledState()
    }

    fun save() {
        val config = AppContext.config
        config.speedLimiterEnabled = tglSpeedLimiter.isSelected
        config.speedLimit = spnSpeedLimiter.value as Int
        config.maxParallelDownloads = cmbMaxConn.selectedItem as Int
        config.maxSegments = cmbSplit.selectedItem as Int
        config.maxRetries = cmbRetry.selectedItem as Int
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)
}
