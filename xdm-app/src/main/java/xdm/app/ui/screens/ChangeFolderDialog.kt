package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.ChangeFolderPlan
import xdm.app.ChangeFolderResult
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.ui.components.CappedComboBox
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.components.MessageBox
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.chooseFile
import xdm.app.utils.getFileFolder
import xdm.app.utils.px
import xdm.app.utils.rememberHandPickedFolder
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Window
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * Change folder for a stopped download (docs/download-categories.md §6.1): pick the new folder from the
 * Save to list (current folder, folder history, default folder) or Browse, then Move. The download keeps
 * its category; the folder joins the history but does not become the None folder.
 * [move] checks again whether the move is allowed, so the download may change state while this is open. *
 * Not modal (docs/windows-and-modality.md): nothing waits on it, and a modal one blocked every other
 * window, the browser's new-download windows included. One per download ([xdm.app.utils.SingleWindows]).
 */
class ChangeFolderDialog(owner: Window?, private val record: DbRecord) : JDialog(owner) {
    private val folderModel = DefaultComboBoxModel<String>()
    private val cmbFolder = CappedComboBox(folderModel, maxWidth = 360)
    private val btnBrowse = DestinationPicker.fieldIconButton(xdm.app.utils.RemixIcon.FOLDER_FILL)
    private val btnMove = JButton(text("CF_MOVE"))

    init {
        title = text("CF_TITLE")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        val current = getFileFolder(record)?.second?.let { File(it).absolutePath }
        val config = AppContext.config
        (listOfNotNull(current) + config.folderHistory + config.defaultDownloadFolder)
            .map { File(it).absolutePath }.distinct().forEach { folderModel.addElement(it) }
        cmbFolder.selectedIndex = 0

        val form = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(14, 14, 6, 14) }
        form.add(JLabel(text("LBL_SAVE_TO")).apply { horizontalAlignment = SwingConstants.RIGHT }, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST; insets = scaledInsets(5, 0, 5, 10); gridx = 0; gridy = 0
        })
        form.add(cmbFolder, GridBagConstraints().apply {
            weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = scaledInsets(5, 0, 5, 5); gridx = 1; gridy = 0
        })
        form.add(btnBrowse, GridBagConstraints().apply { insets = scaledInsets(5, 0, 5, 0); gridx = 2; gridy = 0 })
        val h = cmbFolder.preferredSize.height
        btnBrowse.preferredSize = Dimension(h, h)

        btnBrowse.addActionListener {
            val start = generateSequence(File(selectedFolder())) { it.parentFile }.firstOrNull { it.isDirectory }
            chooseFile(this, directoriesOnly = true, currentDir = start)?.let {
                val path = it.absolutePath
                if (folderModel.getIndexOf(path) < 0) folderModel.insertElementAt(path, 0)
                cmbFolder.selectedItem = path
            }
        }
        btnMove.addActionListener { move() }
        val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }
        sameWidth(btnMove, btnCancel)
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = ScaledEmptyBorder(6, 14, 14, 14)
            add(Box.createHorizontalGlue())
            add(btnCancel)
            add(Box.createRigidArea(scaledSize(10, 0)))
            add(btnMove)
        }
        contentPane.layout = BorderLayout()
        contentPane.add(form, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        rootPane.defaultButton = btnMove
        pack()
        size = Dimension(maxOf(width, 520.px), height)
        setLocationRelativeTo(owner)
    }

    private fun selectedFolder(): String = cmbFolder.selectedItem as? String ?: AppContext.config.defaultDownloadFolder

    private fun move() {
        val folder = selectedFolder()
        val downloader = AppContext.downloader
        when (downloader.changeFolderPlan(record.id, folder)) {
            ChangeFolderPlan.SAME, ChangeFolderPlan.NOT_ALLOWED -> return dispose()
            ChangeFolderPlan.PART_UNAVAILABLE -> {
                MessageBox.show(this, text("CF_TITLE"), text("CF_PART_UNAVAILABLE"))
                return
            }
            ChangeFolderPlan.MOVE -> Unit
        }
        rememberHandPickedFolder(folder, underNone = false)
        val owner = owner
        dispose()
        downloader.changeFolder(record.id, folder) { result ->
            val key = when (result) {
                ChangeFolderResult.DONE, ChangeFolderResult.CANCELLED -> null
                ChangeFolderResult.NO_SPACE -> "CF_NO_SPACE"
                ChangeFolderResult.FAILED -> "CF_FAILED"
            }
            if (key != null) SwingUtilities.invokeLater {
                MessageBox.show(owner, text("CF_TITLE"), text(key))
            }
        }
    }
}
