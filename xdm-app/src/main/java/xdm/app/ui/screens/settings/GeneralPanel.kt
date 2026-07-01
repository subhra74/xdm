package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.chooseFile
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class GeneralPanel : JPanel() {
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
    private val chkShowDwnPrg = JCheckBox(I8N.text("SHOW_DWN_PRG")).apply {
        padding(this, 10)
        alignmentX = LEFT_ALIGNMENT
    }
    private val chkShowComplete = JCheckBox(I8N.text("SHOW_DWN_COMPLETE")).apply { padding(this, 10) }
    private val chkStartAutoDwn = JCheckBox(I8N.text("LBL_START_AUTO")).apply { padding(this, 10) }
    private val chkOverwrite = JCheckBox(I8N.text("LBL_OVERWRITE_EXISTING")).apply { padding(this, 10) }
    private val btnBrowse1 = JButton("...").apply {
        addActionListener {
            chooseFolder(txtTmpDir)
        }
    }
    private val btnBrowse2 = JButton("...").apply {
        addActionListener {
            chooseFolder(txtDwnDir)
        }
    }
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

        val lblTitle = JLabel(I8N.text("SETTINGS_GENERAL")).apply {
            padding(this, 10, topPadding = true)
            font = font.deriveFont(16.0f)
        }
        add(lblTitle)

        add(chkShowDwnPrg)

        add(chkShowComplete)

        add(chkStartAutoDwn)

        //add(chkOverwrite)

        val panelSp = JPanel().apply { padding(this, 10) }
        panelSp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelSp)
        panelSp.setLayout(BoxLayout(panelSp, BoxLayout.X_AXIS))

        panelSp.add(chkSpeedLimiter)
        panelSp.add(Box.createHorizontalGlue())
        panelSp.add(spnSpeedLimiter)


        val lblTempFolder = JLabel(I8N.text("LBL_TEMP_FOLDER")).apply { padding(this, 5) }
        add(lblTempFolder)

        val panelTemp = JPanel().apply { padding(this, 10) }
        panelTemp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelTemp)
        panelTemp.setLayout(BoxLayout(panelTemp, BoxLayout.X_AXIS))

        panelTemp.add(txtTmpDir)

        panelTemp.add(Box.createRigidArea(Dimension(10, 10)))

        panelTemp.add(btnBrowse1)

        val lblDefFolder = JLabel(I8N.text("SETTINGS_FOLDER")).apply { padding(this, 5) }
        add(lblDefFolder)

        val panelDefFolder = JPanel().apply { padding(this, 10) }
        panelDefFolder.setAlignmentX(LEFT_ALIGNMENT)
        add(panelDefFolder)
        panelDefFolder.setLayout(BoxLayout(panelDefFolder, BoxLayout.X_AXIS))
        panelDefFolder.add(txtDwnDir)
        panelDefFolder.add(Box.createRigidArea(Dimension(10, 10)))
        panelDefFolder.add(btnBrowse2)

        val panelMaxConn = Box.createHorizontalBox()
        panelMaxConn.setAlignmentX(LEFT_ALIGNMENT)
        add(panelMaxConn)

        val lblMaxConn = JLabel(I8N.text("MSG_MAX_DOWNLOAD"))
        panelMaxConn.add(lblMaxConn)
        panelMaxConn.add(Box.createHorizontalGlue())
        panelMaxConn.add(cmbMaxConn)

        langProp = Properties()
        langProp.load(
            InputStreamReader(
                GeneralPanel::class.java.getResourceAsStream("/lang/map") ?: FileInputStream("lang/map"),
                StandardCharsets.UTF_8
            )
        )
        langModel.addAll(langProp.values.map { it.toString() })

        val panelLang = Box.createHorizontalBox().apply {
            padding(this, 10, topPadding = true, bottomPadding = true)
        }
        panelLang.setAlignmentX(LEFT_ALIGNMENT)
        add(panelLang)

        val lblLang = JLabel(I8N.text("MSG_LANG1"))
        panelLang.add(lblLang)
        panelLang.add(Box.createHorizontalGlue())
        panelLang.add(cmbLang)

        val panelTheme = Box.createHorizontalBox().apply {
            padding(this, 10, topPadding = true, bottomPadding = true)
        }
        panelTheme.setAlignmentX(LEFT_ALIGNMENT)
        add(panelTheme)

        val lblTheme = JLabel(I8N.text("MSG_THEME"))
        panelTheme.add(lblTheme)
        panelTheme.add(Box.createHorizontalGlue())
        panelTheme.add(cmbTheme)

        add(JLabel(I8N.text("MSG_LANG2")).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        add(Box.createHorizontalGlue())
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
        return Insets(10, 10, 10, 10)
    }

    private fun chooseFolder(textField: JTextField) {
        chooseFile(this, directoriesOnly = true)?.let {
            textField.text = it.absolutePath
        }
    }

    private data class LanguageEntry(val code: String, val name: String)
}
