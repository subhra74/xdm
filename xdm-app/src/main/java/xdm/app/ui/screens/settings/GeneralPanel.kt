package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCompleteNotification
import xdm.app.I8N
import xdm.app.ListSelectionMode
import xdm.app.utils.RemixIcon
import xdm.app.utils.mac.MacNotifications
import xdm.app.utils.offerMacNotificationSettings
import xdm.app.utils.openFolderExternal
import xdm.core.util.Logger
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.SwingUtilities

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

    private val optListMode = SettingsOptionGroup(
        listOf(
            SettingsOption(ListSelectionMode.CLASSIC, I8N.text("LIST_MODE_CLASSIC"), RemixIcon.FILE_LIST_2_LINE),
            SettingsOption(ListSelectionMode.MODERN, I8N.text("LIST_MODE_MODERN"), RemixIcon.CHECKBOX_LINE),
        )
    )

    private val langModel = DefaultComboBoxModel<String>()
    private val cmbLang = rounded(JComboBox(langModel)).apply {
        preferredSize = Dimension(190.px, preferredSize.height)
        maximumSize = preferredSize
    }
    private val languages = I8N.languages()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // Ask while the user is choosing: the macOS prompt appears now rather than at the first
        // finished download, and if notifications were turned off earlier, say so.
        optOnComplete.onChange = {
            if (optOnComplete.selected == DownloadCompleteNotification.NOTIFICATION) {
                MacNotifications.requestPermission {
                    SwingUtilities.invokeLater { if (isShowing) offerMacNotificationSettings(this) }
                }
            }
        }

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

        langModel.addAll(languages.map { it.name })

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_APPEARANCE"),
                settingsFullRow(I8N.text("MSG_THEME"), I8N.text("MSG_RESTART_NEEDED"), optTheme),
                settingsFullRow(I8N.text("LIST_MODE"), I8N.text("LIST_MODE_SUB"), optListMode),
                settingsRow(I8N.text("MSG_LANG1"), I8N.text("MSG_LANG_SUB") + "\n" + I8N.text("MSG_RESTART_NEEDED"), cmbLang),
                settingsRow(
                    I8N.text("LANG_FOLDER"), I8N.text("LANG_FOLDER_SUB"),
                    settingsButton(I8N.text("LANG_FOLDER_OPEN"), ::openTranslationFolder)
                ),
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
        optListMode.selected = config.listSelectionMode
        languages.indexOfFirst { it.code == config.lang }.takeIf { it >= 0 }?.let { cmbLang.selectedIndex = it }
    }

    fun save() {
        val config = AppContext.config
        config.showDownloadProgressWindow = tglShowDwnPrg.isSelected
        config.startDownloadAutomatically = tglStartAutoDwn.isSelected
        config.overwriteExistingFiles = tglOverwrite.isSelected
        config.downloadCompleteNotification = optOnComplete.selected
        config.theme = optTheme.selected
        config.listSelectionMode = optListMode.selected
        languages.getOrNull(cmbLang.selectedIndex)?.let { config.lang = it.code }
    }

    /** Translators drop their `<code>.txt` here to preview it (docs/i18n.md); created on first use. */
    private fun openTranslationFolder() {
        val dir = I8N.overrideDir
        dir.mkdirs()
        runCatching { openFolderExternal(null, dir.path) }.onFailure { Logger.error(it) }
    }

    override fun getInsets(): Insets = scaledInsets(18, 24, 24, 24)
}
