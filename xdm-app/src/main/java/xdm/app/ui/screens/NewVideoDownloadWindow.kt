package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.AppContext.config
import xdm.app.I8N.text
import xdm.app.utils.createSVGIcon
import xdm.app.utils.sameWidth
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.util.*
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
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

    init {
        initUI()
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
            icon = createSVGIcon("file-line.svg", 36, Color.GRAY)
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

        val btnBrowse = JButton(createSVGIcon("folder-fill.svg", 16, Color.GRAY))
        val gbcBtnBrowse = GridBagConstraints().apply {
            insets = Insets(5, 0, 5, 5)
            gridx = 4
            gridy = 2
        }
        contentPane.add(btnBrowse, gbcBtnBrowse)

        val lblIgnore = JLabel(text("ND_IGNORE_URL"))
        lblIgnore.verticalAlignment = SwingConstants.TOP
        val gbcLblIgnore = GridBagConstraints().apply {
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
            anchor = GridBagConstraints.NORTHWEST
            gridwidth = 4
            insets = Insets(10, 0, 5, 5)
            gridx = 1
            gridy = 3
        }
        contentPane.add(lblIgnore, gbcLblIgnore)

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

            var folder: String? = null
            if (cmbSaveIn.selectedIndex != 0) {
                folder = cmbSaveIn.selectedItem?.toString()
            }

            AppContext.downloader.addVideoDownload(videoId, name, folder, (folder == null))
        } finally {
            dispose()
        }
    }

    private fun adjustSize() {
        var dim = preferredSize
        dim = Dimension(max(dim.width.toDouble(), 500.0).toInt(), max(dim.height.toDouble(), 270.0).toInt())
        size = dim
    }

    fun showWindow(vid: Long, fileName: String, fileSize: Long?, contentType: String?) {
        this.videoId = vid
        this.adjustSize()
        this.setLocationRelativeTo(null)
        modelSaveIn.removeAllElements()
        modelSaveIn.addAll(config.recentFolders)
        if (config.autoSelectFolder) {
            cmbSaveIn.setSelectedIndex(0)
        } else {
            cmbSaveIn.setSelectedIndex(config.folderIndex + 1)
        }
        txtFileName.text = FileUtils.sanitizeFileName(fileName)
        fileSize?.let {
            lblFileInfo.text = FormatHelper.formatSize(it.toDouble())
        }
        this.isVisible = true
    }
}
