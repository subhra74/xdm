package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCompleteNotification
import xdm.app.I8N
import xdm.app.utils.RemixIcon
import java.awt.Dimension
import java.awt.Insets
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

/** Behaviour and appearance: what XDM does when a download starts and finishes, and how it looks. */
class GeneralPanel : SettingsPanel() {
    private val tglShowDwnPrg = SettingsToggle()
    private val tglStartAutoDwn = SettingsToggle()
    private val tglOverwrite = SettingsToggle()

    private val optOnComplete = SettingsOptionGroup(
        listOf(
            SettingsOption(DownloadCompleteNotification.DIALOG, I8N.text("ON_COMPLETE_DIALOG_SHORT"), RemixIcon.WINDOW_2_LINE),
            SettingsOption(
                DownloadCompleteNotification.NOTIFICATION,
                I8N.text("ON_COMPLETE_NOTIFICATION_SHORT"),
                RemixIcon.NOTIFICATION_3_LINE
            ),
            SettingsOption(DownloadCompleteNotification.NONE, I8N.text("ON_COMPLETE_NOTHING_SHORT"), RemixIcon.CLOSE_CIRCLE_LINE),
        )
    )

    private val themeCodes = listOf("dark", "light")
    private val optTheme = SettingsOptionGroup(
        listOf(
            SettingsOption("dark", I8N.text("THEME_DARK"), RemixIcon.MOON_FILL),
            SettingsOption("light", I8N.text("THEME_LIGHT"), RemixIcon.SUN_FILL),
        )
    )

    private val langModel = DefaultComboBoxModel<String>()
    private val cmbLang = rounded(JComboBox(langModel)).apply {
        preferredSize = Dimension(190, preferredSize.height)
        maximumSize = preferredSize
    }
    private val langProp = Properties()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_GENERAL")))

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_BEHAVIOR"),
                settingsRow(I8N.text("SHOW_DWN_PRG"), I8N.text("SHOW_DWN_PRG_SUB"), tglShowDwnPrg),
                settingsRow(I8N.text("LBL_START_AUTO"), I8N.text("LBL_START_AUTO_SUB"), tglStartAutoDwn),
                settingsRow(I8N.text("LBL_OVERWRITE_EXISTING"), I8N.text("LBL_OVERWRITE_EXISTING_SUB"), tglOverwrite),
            )
        )
        add(settingsGap())

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_ON_COMPLETE"),
                settingsFullRow(I8N.text("LBL_ON_COMPLETE"), I8N.text("LBL_ON_COMPLETE_SUB"), optOnComplete),
            )
        )
        add(settingsGap())

        langProp.load(
            InputStreamReader(
                GeneralPanel::class.java.getResourceAsStream("/lang/map") ?: FileInputStream("lang/map"),
                StandardCharsets.UTF_8
            )
        )
        langModel.addAll(langProp.values.map { it.toString() })

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_APPEARANCE"),
                settingsFullRow(I8N.text("MSG_THEME"), null, optTheme),
                settingsRow(I8N.text("MSG_LANG1"), I8N.text("MSG_LANG2"), cmbLang),
            )
        )

        add(Box.createVerticalGlue())
    }

    fun load() {
        val config = AppContext.config
        tglShowDwnPrg.isSelected = config.showDownloadProgressWindow
        tglStartAutoDwn.isSelected = config.startDownloadAutomatically
        tglOverwrite.isSelected = config.overwriteExistingFiles
        optOnComplete.selected = config.downloadCompleteNotification
        optTheme.selected = config.theme.lowercase().takeIf { themeCodes.contains(it) } ?: "dark"
        langProp[config.lang]?.let { cmbLang.selectedItem = it }
    }

    fun save() {
        val config = AppContext.config
        config.showDownloadProgressWindow = tglShowDwnPrg.isSelected
        config.startDownloadAutomatically = tglStartAutoDwn.isSelected
        config.overwriteExistingFiles = tglOverwrite.isSelected
        config.downloadCompleteNotification = optOnComplete.selected
        config.theme = optTheme.selected
        for (key in langProp.keys) {
            if (langProp[key] == cmbLang.selectedItem) {
                config.lang = key as String
                break
            }
        }
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)
}
