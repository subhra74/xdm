package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.ui.components.BrowserExtensionPanel
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/** What the browser extension hands over: which file types are captured, and from which sites. */
class BrowserMonitorPanel : SettingsPanel() {
    private val txtFileExt = extensionArea()
    private val txtVidExt = extensionArea()
    private val txtBlockedHosts = extensionArea()
    private val cmbMinVidSize = rounded(JComboBox<Long>()).apply {
        listOf(1L, 5L, 10L, 50L, 100L).forEach { addItem(it) }
        preferredSize = Dimension(110.px, preferredSize.height)
        maximumSize = preferredSize
    }
    private val tglGetServerTime = SettingsToggle()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_MONITORING")))

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_BROWSERS"),
                settingsFullRow(null, I8N.text("SETTINGS_SEC_BROWSERS_SUB"), BrowserExtensionPanel()),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_FILETYPES"),
                settingsFullRow(
                    I8N.text("DESC_FILETYPES"),
                    I8N.text("DESC_FILETYPES_SUB"),
                    areaWithReset(txtFileExt) { AppContext.config.defFileExtensions }
                ),
                settingsFullRow(
                    I8N.text("DESC_VIDEOTYPES"),
                    I8N.text("DESC_VIDEOTYPES_SUB"),
                    areaWithReset(txtVidExt) { AppContext.config.defVideoExtensions }
                ),
                settingsRow(
                    I8N.text("LBL_MIN_VIDEO_SIZE"),
                    I8N.text("LBL_MIN_VIDEO_SIZE_SUB"),
                    Box.createHorizontalBox().apply {
                        add(cmbMinVidSize)
                        add(Box.createRigidArea(scaledSize(8, 0)))
                        add(JLabel("MB").apply { foreground = settingsMutedColor() })
                    }
                ),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_EXCEPTIONS"),
                settingsFullRow(
                    I8N.text("DESC_SITEEXCEPTIONS"),
                    I8N.text("DESC_SITEEXCEPTIONS_SUB"),
                    areaWithReset(txtBlockedHosts) { AppContext.config.defBlockedHosts }
                ),
                settingsRow(I8N.text("LBL_GET_TIMESTAMP"), I8N.text("LBL_GET_TIMESTAMP_SUB"), tglGetServerTime),
            )
        )

        add(Box.createVerticalGlue())
    }

    private fun extensionArea(): JTextArea = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
        border = ScaledEmptyBorder(7, 9, 7, 9)
    }

    /**
     * A rounded, wrapping list of comma-separated values with its reset button underneath.
     * `lineWrap` keeps a long list inside the pane's width, so it only ever scrolls vertically.
     */
    private fun areaWithReset(area: JTextArea, defaults: () -> List<String>): JComponent {
        val scroll = rounded(JScrollPane(area)).apply {
            alignmentX = LEFT_ALIGNMENT
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(preferredSize.width, 78.px)
            maximumSize = Dimension(Int.MAX_VALUE, 78.px)
        }
        return Box.createVerticalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(scroll)
            add(Box.createRigidArea(scaledSize(0, 9)))
            add(settingsLeftAligned(settingsButton(I8N.text("DESC_DEF")) {
                area.text = defaults().joinToString(", ")
            }))
        }
    }

    fun load() {
        val config = AppContext.config
        txtFileExt.text = config.fileExtensions.joinToString(", ")
        txtVidExt.text = config.videoExtensions.joinToString(", ")
        txtBlockedHosts.text = config.blockedHosts.joinToString(", ")
        cmbMinVidSize.selectedItem = config.minVideoSize
        tglGetServerTime.isSelected = config.getServerTime
    }

    fun save() {
        val config = AppContext.config
        config.fileExtensions = txtFileExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.videoExtensions = txtVidExt.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.blockedHosts = txtBlockedHosts.text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        config.minVideoSize = cmbMinVidSize.selectedItem as Long
        config.getServerTime = tglGetServerTime.isSelected
    }

    override fun getInsets(): Insets = scaledInsets(18, 24, 24, 24)
}
