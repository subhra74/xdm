package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.chooseFile
import xdm.app.utils.createSVGIcon
import xdm.app.utils.fixHeight
import java.awt.Dimension
import java.awt.Insets
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class GeneralPanel : SettingsPanel() {
    private val txtTmpDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val txtDwnDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val spModel = SpinnerNumberModel(100, 0, 10000000, 1)
    private val spnSpeedLimiter = JSpinner(spModel).apply {
        fixHeight(this)
        preferredSize = Dimension(120, preferredSize.height)
        maximumSize = Dimension(120, preferredSize.height)
    }
    private val chkSpeedLimiter = JCheckBox(I8N.text("MSG_SPEED_LIMIT"))
    private val chkShowDwnPrg = JCheckBox(I8N.text("SHOW_DWN_PRG"))
    private val chkShowComplete = JCheckBox(I8N.text("SHOW_DWN_COMPLETE"))
    private val chkStartAutoDwn = JCheckBox(I8N.text("LBL_START_AUTO"))
    private val chkOverwrite = JCheckBox(I8N.text("LBL_OVERWRITE_EXISTING"))
    private val btnBrowse1 = createBrowseButton(txtTmpDir)
    private val btnBrowse2 = createBrowseButton(txtDwnDir)
    private val cmbMaxConn = JComboBox<Int>().apply {
        fixHeight(this)
        addItem(1)
        addItem(2)
        addItem(4)
        addItem(8)
        addItem(16)
        addItem(32)
    }
    private val langModel = DefaultComboBoxModel<String>()
    private val cmbLang = JComboBox<String>(langModel).apply { fixHeight(this) }
    private val langProp: Properties
    private val themeCodes = listOf("dark", "light")
    private val cmbTheme = JComboBox<String>().apply {
        fixHeight(this)
        addItem(I8N.text("THEME_DARK"))
        addItem(I8N.text("THEME_LIGHT"))
    }

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        add(settingsTitle(I8N.text("SETTINGS_GENERAL")))

        // Behaviour
        add(
            settingsCard(
                "settings-4-line.svg", I8N.text("SETTINGS_SEC_BEHAVIOR"),
                settingsLeftAligned(chkShowDwnPrg),
                settingsLeftAligned(chkShowComplete),
                settingsLeftAligned(chkStartAutoDwn),
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Downloads
        val speedRow = settingsRow(chkSpeedLimiter, spnSpeedLimiter)
        val maxConnRow = settingsRow(JLabel(I8N.text("MSG_MAX_DOWNLOAD")), cmbMaxConn)
        add(settingsCard("arrow-up-down-fill.svg", I8N.text("SETTINGS_SEC_DOWNLOADS"), speedRow, maxConnRow))
        add(Box.createRigidArea(Dimension(0, 12)))

        // Folders
        add(
            settingsCard(
                "folder-6-line.svg", I8N.text("SETTINGS_SEC_FOLDERS"),
                folderField(I8N.text("LBL_TEMP_FOLDER"), txtTmpDir, btnBrowse1),
                folderField(I8N.text("SETTINGS_FOLDER"), txtDwnDir, btnBrowse2),
            )
        )
        add(Box.createRigidArea(Dimension(0, 12)))

        // Appearance & language
        langProp = Properties()
        langProp.load(
            InputStreamReader(
                GeneralPanel::class.java.getResourceAsStream("/lang/map") ?: FileInputStream("lang/map"),
                StandardCharsets.UTF_8
            )
        )
        langModel.addAll(langProp.values.map { it.toString() })

        val themeRow = settingsRow(JLabel(I8N.text("MSG_THEME")), cmbTheme)
        val langRow = settingsRow(JLabel(I8N.text("MSG_LANG1")), cmbLang)
        val lblNote = JLabel(I8N.text("MSG_LANG2")).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = settingsMutedColor()
        }
        add(
            settingsCard(
                "sparkling-2-fill.svg", I8N.text("SETTINGS_SEC_APPEARANCE"),
                themeRow, langRow, settingsLeftAligned(lblNote)
            )
        )

        add(Box.createVerticalGlue())
    }

    /** A titled folder input: a caption above a text field with a trailing browse button. */
    private fun folderField(caption: String, field: JTextField, button: JButton): JComponent {
        val box = Box.createVerticalBox().apply { alignmentX = LEFT_ALIGNMENT }
        box.add(settingsLeftAligned(JLabel(caption).apply { foreground = settingsMutedColor() }))
        box.add(Box.createRigidArea(Dimension(0, 5)))
        val inputRow = Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            add(field)
            add(Box.createRigidArea(Dimension(8, 0)))
            add(button)
        }
        box.add(inputRow)
        return box
    }

    private fun createBrowseButton(field: JTextField): JButton {
        return JButton(
            I8N.text("SETTINGS_FOLDER_CHANGE"),
            createSVGIcon("folder-6-line.svg", 16, settingsAccentColor())
        ).apply {
            iconTextGap = 6
            fixHeight(this)
            addActionListener { chooseFolder(field) }
        }
    }

    fun load() {
        val config = AppContext.config
        chkShowDwnPrg.isSelected = config.showDownloadProgressWindow
        chkShowComplete.isSelected = config.showDownloadCompleteWindow
        chkStartAutoDwn.isSelected = config.startDownloadAutomatically
        chkOverwrite.isSelected = config.overwriteExistingFiles
        chkSpeedLimiter.isSelected = config.speedLimiterEnabled
        spnSpeedLimiter.value = config.speedLimit
        txtTmpDir.text = config.tempFolder
        txtDwnDir.text = config.defaultDownloadFolder
        cmbMaxConn.selectedItem = config.maxParallelDownloads
        val langValue = langProp[config.lang]
        if (langValue != null) {
            cmbLang.selectedItem = langValue
        }
        val themeIndex = themeCodes.indexOf(config.theme.lowercase())
        cmbTheme.selectedIndex = if (themeIndex >= 0) themeIndex else 0
    }

    fun save() {
        val config = AppContext.config
        config.showDownloadProgressWindow = chkShowDwnPrg.isSelected
        config.showDownloadCompleteWindow = chkShowComplete.isSelected
        config.startDownloadAutomatically = chkStartAutoDwn.isSelected
        config.overwriteExistingFiles = chkOverwrite.isSelected
        config.speedLimiterEnabled = chkSpeedLimiter.isSelected
        config.speedLimit = spnSpeedLimiter.value as Int
        config.tempFolder = txtTmpDir.text
        config.defaultDownloadFolder = txtDwnDir.text
        config.maxParallelDownloads = cmbMaxConn.selectedItem as Int
        for (key in langProp.keys) {
            if (langProp[key] == cmbLang.selectedItem) {
                config.lang = key as String
                break
            }
        }
        config.theme = themeCodes[cmbTheme.selectedIndex.coerceIn(themeCodes.indices)]
    }

    override fun getInsets(): Insets {
        return Insets(10, 12, 12, 12)
    }

    private fun chooseFolder(textField: JTextField) {
        chooseFile(this, directoriesOnly = true)?.let {
            textField.text = it.absolutePath
        }
    }
}
