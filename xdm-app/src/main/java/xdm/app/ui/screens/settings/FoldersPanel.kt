package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.VolumeHints
import xdm.app.utils.chooseFile
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Where files land: the two folders every download passes through, and the category rules
 * that pick a folder automatically. The two belong on one page because the folder a
 * category points at is only meaningful next to the default it overrides.
 */
class FoldersPanel : SettingsPanel() {
    private val txtTmpDir = rounded(JTextField()).apply { columns = 10 }
    private val txtDwnDir = rounded(JTextField()).apply { columns = 10 }
    private val lblVolumeHint = settingsHint("").apply { isVisible = false }
    private val categorySection = CategorySection()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        add(settingsTitle(I8N.text("SETTINGS_FOLDERS")))

        add(
            settingsSection(
                I8N.text("SETTINGS_SEC_FOLDERS"),
                settingsFullRow(
                    I8N.text("SETTINGS_FOLDER"),
                    I8N.text("SETTINGS_FOLDER_SUB"),
                    folderInput(txtDwnDir)
                ),
                settingsFullRow(
                    I8N.text("LBL_TEMP_FOLDER"),
                    I8N.text("LBL_TEMP_FOLDER_SUB"),
                    Box.createVerticalBox().apply {
                        alignmentX = LEFT_ALIGNMENT
                        add(folderInput(txtTmpDir))
                        add(Box.createRigidArea(Dimension(0, 8)))
                        add(settingsLeftAligned(lblVolumeHint))
                    }
                ),
            )
        )
        add(settingsGap())

        add(categorySection.component)

        add(Box.createVerticalGlue())
    }

    /** A rounded path field with a plain text button beside it. */
    private fun folderInput(field: JTextField): JComponent =
        Box.createHorizontalBox().apply {
            alignmentX = LEFT_ALIGNMENT
            val h = field.preferredSize.height
            field.maximumSize = Dimension(Int.MAX_VALUE, h)
            add(field)
            add(Box.createRigidArea(Dimension(10, 0)))
            add(settingsButton(I8N.text("SETTINGS_FOLDER_CHANGE")) { chooseFolder(field) })
            maximumSize = Dimension(Int.MAX_VALUE, h)
        }

    private fun chooseFolder(textField: JTextField) {
        chooseFile(this, directoriesOnly = true)?.let {
            textField.text = it.absolutePath
            refreshVolumeHint()
        }
    }

    /**
     * Downloads are written to the temp folder and moved at the end. When the two folders are on
     * different volumes that move becomes a full copy, so say so where the folders are chosen.
     */
    private fun refreshVolumeHint() {
        val different = VolumeHints.isDifferentVolume(txtDwnDir.text, txtTmpDir.text)
        lblVolumeHint.isVisible = different
        if (different) lblVolumeHint.text = I8N.text("MSG_DIFFERENT_VOLUME")
    }

    fun load() {
        val config = AppContext.config
        txtTmpDir.text = config.tempFolder
        txtDwnDir.text = config.defaultDownloadFolder
        categorySection.load()
        refreshVolumeHint()
    }

    fun save() {
        val config = AppContext.config
        config.tempFolder = txtTmpDir.text
        config.defaultDownloadFolder = txtDwnDir.text
        categorySection.save()
    }

    override fun getInsets(): Insets = Insets(18, 24, 24, 24)
}
