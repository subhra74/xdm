package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.CategoryStyle
import xdm.app.utils.chooseFile
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.isAutoCategorySelected
import xdm.app.utils.persistFolderChoiceOnChange
import xdm.app.utils.populateSaveInFolders
import xdm.app.utils.rememberFolderChoice
import xdm.app.utils.selectedBaseFolder
import xdm.app.utils.sameWidth
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.util.*
import xdm.integration.EventChannel
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

class NewVideoDownloadWindow : JDialog() {
    private lateinit var txtFileName: JTextField
    private lateinit var cmbSaveIn: JComboBox<String>
    private lateinit var btnDownload: JButton
    private lateinit var modelSaveIn: DefaultComboBoxModel<String>
    private lateinit var lblFileInfo: JLabel
    private var originalFileName: String? = null
    private var videoId: Long = -1

    private var taskInfo: StreamingDownloadTaskInfo? = null
    private var selectedFolder: String? = null

    init {
        initUI()
        attachFileNameChangeListener()
        defaultCloseOperation = DISPOSE_ON_CLOSE
    }

    private fun initUI() {
        isAlwaysOnTop = true
        title = text("ND_TITLE")
        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MIN_VALUE)
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, Double.MIN_VALUE)
        }
        contentPane.run {
            layout = gridBagLayout
            background = UIManager.getColor("Table.background")
        }

        val lblFile = JLabel(text("ND_FILE")).apply {
            horizontalAlignment = SwingConstants.RIGHT
        }
        val gbcLblFile = GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(15, 20, 5, 10)
            gridx = 0
            gridy = 1
        }
        contentPane.add(lblFile, gbcLblFile)

        txtFileName = JTextField()
        val gbcTxtFileName = GridBagConstraints().apply {
            gridwidth = 4
            weightx = 1.0
            insets = Insets(15, 0, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 1
            gridy = 1
        }
        contentPane.add(txtFileName, gbcTxtFileName)
        txtFileName.columns = 10

        lblFileInfo = JLabel().apply {
            icon = createIcon(RemixIcon.FILE_LINE, 36, Color.GRAY)
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalTextPosition = SwingConstants.CENTER
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            text = "---"
            preferredSize = Dimension(
                preferredSize.width + 30, preferredSize.height
            )
        }
        val gbcLblFileInfo = GridBagConstraints().apply {
            insets = Insets(20, 5, 5, 10)
            gridheight = 3
            gridx = 5
            gridy = 0
        }
        contentPane.add(lblFileInfo, gbcLblFileInfo)

        val lblSaveIn = JLabel(text("LBL_SAVE_IN")).apply {
            horizontalAlignment = SwingConstants.RIGHT
        }
        val gbcLblSaveIn = GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(5, 20, 5, 10)
            gridx = 0
            gridy = 2
        }
        contentPane.add(lblSaveIn, gbcLblSaveIn)

        modelSaveIn = DefaultComboBoxModel()
        cmbSaveIn = JComboBox(modelSaveIn)

        val gbcCmbSaveIn = GridBagConstraints().apply {
            gridwidth = 3
            weightx = 1.0
            insets = Insets(5, 0, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 1
            gridy = 2
        }
        contentPane.add(cmbSaveIn, gbcCmbSaveIn)
        persistFolderChoiceOnChange(cmbSaveIn)

        val btnBrowse = JButton(createIcon(RemixIcon.FOLDER_FILL, 16, Color.GRAY))
        val gbcBtnBrowse = GridBagConstraints().apply {
            insets = Insets(5, 0, 5, 5)
            gridx = 4
            gridy = 2
        }
        contentPane.add(btnBrowse, gbcBtnBrowse)
        btnBrowse.addActionListener {
            val selected = chooseFile(
                this@NewVideoDownloadWindow,
                directoriesOnly = true,
                currentDir = File(selectedBaseFolder(cmbSaveIn))
            )
            if (selected != null) {
                val path = selected.absolutePath
                val idx = modelSaveIn.getIndexOf(path).takeIf { it >= 0 } ?: run {
                    modelSaveIn.addElement(path)
                    modelSaveIn.size - 1
                }
                cmbSaveIn.selectedIndex = idx
            }
        }

        val lblFreeSpace = JLabel("---").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val lblIgnore = JLabel(text("ND_IGNORE_URL")).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("ProgressBar.foreground")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ignoreHost()
                }
            })
        }

        // Glue rather than a fixed gap: the link stays on the bottom edge as the dialog is resized.
        val infoBox = Box.createVerticalBox().apply {
            add(lblFreeSpace)
            add(Box.createVerticalGlue())
            add(lblIgnore)
            add(Box.createRigidArea(Dimension(0, 10)))
        }
        val gbcInfoBox = GridBagConstraints().apply {
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
            anchor = GridBagConstraints.NORTHWEST
            gridwidth = 4
            insets = Insets(5, 0, 5, 5)
            gridx = 1
            gridy = 3
        }
        contentPane.add(infoBox, gbcInfoBox)

        cmbSaveIn.addItemListener {
            val folder = selectedBaseFolder(cmbSaveIn)
            if (selectedFolder != folder) {
                selectedFolder = folder
                val freeSpace = File(folder).freeSpace
                lblFreeSpace.text = "${text("MSG_FREE_SPACE")} ${FormatHelper.formatSize(freeSpace.toDouble())}"
            }
        }

        val panel = JPanel()
        panel.border = EmptyBorder(10, 15, 10, 15)
        val gcPanel = GridBagConstraints().apply {
            weightx = 1.0
            gridwidth = 6
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 4
        }
        contentPane.add(panel, gcPanel)
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

//        val btnQueue = JButton(text("ND_QUEUE"))
//        panel.add(btnQueue)

        panel.add(Box.createHorizontalGlue())
        val rigidArea1 = Box.createRigidArea(Dimension(80, 20))
        panel.add(rigidArea1)

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { e: ActionEvent? -> dispose() }
        panel.add(btnCancel)

        val rigidArea = Box.createRigidArea(Dimension(10, 30))
        panel.add(rigidArea)

        btnDownload = JButton(text("ND_DOWNLOAD"))
        btnDownload.addActionListener { _ -> downloadNow() }
        panel.add(btnDownload)

        getRootPane().defaultButton = btnDownload

        sameWidth(btnDownload, btnCancel)

        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnDownload.requestFocusInWindow()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
    }


    private fun downloadNow() {
        val file = txtFileName.text
        if (StringUtils.isNullOrEmptyOrBlank(file)) {
            JOptionPane.showMessageDialog(this, text("MSG_NO_FILE"))
            return
        }

        try {
            val name = FileUtils.sanitizeFileName(file)!!
            //TODO: Check FFmpeg required

            val auto = isAutoCategorySelected(cmbSaveIn)
            rememberFolderChoice(cmbSaveIn)
            AppContext.downloader.addVideoDownload(videoId, name, selectedBaseFolder(cmbSaveIn), auto)
        } finally {
            dispose()
        }
    }

    /** Packing first is what makes the window insets known, so the content is never clipped. */
    private fun adjustSize() {
        pack()
        size = Dimension(max(width, 500), max(height, 240))
    }

    private fun ignoreHost() {
        val tracker = AppContext.videoTracker
        val url = tracker.getHttpVideo(videoId)?.url
            ?: tracker.getHlsVideo(videoId)?.url
            ?: tracker.getDashVideo(videoId)?.url
        val host = try {
            url?.let { URI(it.trim()).host }
        } catch (e: Exception) {
            Logger.info(e)
            null
        }
        if (!host.isNullOrBlank()) {
            val config = AppContext.config
            if (config.blockedHosts.none { it.equals(host, ignoreCase = true) }) {
                config.blockedHosts = config.blockedHosts + host
                config.save()
                // The extension gates capture on this list, so it is useless until it arrives
                // there. Without this the parked poll keeps waiting and the new host only lands
                // on the next /sync - up to a watchdog interval later, by which time the user has
                // already retried the link and been captured again.
                EventChannel.notifyChanged()
            }
        }
        dispose()
    }

    fun showWindow(vid: Long, fileName: String, fileSize: Long?, contentType: String?) {
        this.videoId = vid
        populateSaveInFolders(modelSaveIn, cmbSaveIn)
        txtFileName.text = FileUtils.sanitizeFileName(fileName)
        lblFileInfo.text = fileSize?.let { FormatHelper.formatSize(it.toDouble()) } ?: "---"

        this.adjustSize()
        this.setLocationRelativeTo(null)
        this.isVisible = true
    }

    /** Picks the icon the file's category uses, so the preview matches the row it will become. */
    private fun updateFileIcon() {
        val glyph = CategoryStyle.lineVariant(CategoryStyle.iconForFile(txtFileName.text.orEmpty()))
        lblFileInfo.icon = createIcon(glyph, 36, Color.GRAY)
    }

    private fun attachFileNameChangeListener() {
        txtFileName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateFileIcon()

            override fun removeUpdate(e: DocumentEvent) = updateFileIcon()

            override fun changedUpdate(e: DocumentEvent) = updateFileIcon()
        })
    }
}
