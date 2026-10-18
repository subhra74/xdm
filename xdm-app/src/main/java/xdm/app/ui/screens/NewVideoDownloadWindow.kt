package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.CategoryStyle
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.components.StorageStatusLabel
import xdm.app.ui.components.TempFolderCards
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.util.*
import xdm.integration.EventChannel
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

class NewVideoDownloadWindow : JDialog() {
    private lateinit var txtFileName: JTextField
    private lateinit var btnDownload: JButton
    private lateinit var picker: DestinationPicker
    private lateinit var lblFileInfo: JLabel
    private lateinit var cmbSegments: JComboBox<Int>
    private lateinit var storageStatus: StorageStatusLabel
    private lateinit var tempFolderCards: TempFolderCards
    private var fileSize: Long? = null
    private var originalFileName: String? = null
    private var videoId: Long = -1

    private var taskInfo: StreamingDownloadTaskInfo? = null

    init {
        initUI()
        // After the form is built: its content pane becomes the first card.
        tempFolderCards = TempFolderCards(this, fitMain = ::adjustSize, onSaved = { storageStatus.refreshNow() })
        attachFileNameChangeListener()
        defaultCloseOperation = DISPOSE_ON_CLOSE
    }

    private fun initUI() {
        isAlwaysOnTop = true
        title = text("ND_TITLE")
        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MIN_VALUE)
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, Double.MIN_VALUE)
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
            insets = scaledInsets(15, 20, 5, 10)
            gridx = 0
            gridy = 1
        }
        contentPane.add(lblFile, gbcLblFile)

        txtFileName = JTextField()
        val gbcTxtFileName = GridBagConstraints().apply {
            gridwidth = 4
            weightx = 1.0
            insets = scaledInsets(15, 0, 5, 5)
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
                preferredSize.width + 30.px, preferredSize.height
            )
        }
        val gbcLblFileInfo = GridBagConstraints().apply {
            insets = scaledInsets(20, 5, 5, 10)
            // File, category and save-to rows.
            gridheight = 4
            gridx = 5
            gridy = 0
        }
        contentPane.add(lblFileInfo, gbcLblFileInfo)

        // Captured videos count as browser captures: the category rules preselect them.
        picker = DestinationPicker(this, capture = true, onChanged = { updateStorageStatus() })
        picker.addTo(contentPane, firstRow = 2)

        val lblIgnore = JLabel(text("ND_IGNORE_PAGE")).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("ProgressBar.foreground")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ignoreHost()
                }
            })
        }

        storageStatus = StorageStatusLabel { dest, size -> tempFolderCards.showTempFolder(dest, size) }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Glue rather than a fixed gap: the link stays on the bottom edge as the dialog is resized.
        val infoBox = Box.createVerticalBox().apply {
            add(storageStatus)
            // A minimum gap, so the ignore link never touches the storage line in a short dialog.
            add(Box.createRigidArea(scaledSize(0, 8)))
            add(Box.createVerticalGlue())
            add(lblIgnore)
            add(Box.createRigidArea(scaledSize(0, 10)))
        }
        val gbcInfoBox = GridBagConstraints().apply {
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
            anchor = GridBagConstraints.NORTHWEST
            gridwidth = 4
            insets = scaledInsets(5, 0, 5, 5)
            gridx = 1
            gridy = 4
        }
        contentPane.add(infoBox, gbcInfoBox)

        val panel = JPanel()
        panel.border = ScaledEmptyBorder(10, 15, 10, 15)
        val gcPanel = GridBagConstraints().apply {
            weightx = 1.0
            gridwidth = 6
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 5
        }
        contentPane.add(panel, gcPanel)
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

//        val btnQueue = JButton(text("ND_QUEUE"))
//        panel.add(btnQueue)

        panel.add(JLabel(text("ND_SEGMENTS")))
        panel.add(Box.createRigidArea(scaledSize(10, 30)))

        cmbSegments = JComboBox(arrayOf(1, 2, 4, 8, 16, 32, 64)).apply {
            selectedItem = AppContext.config.maxSegments
            maximumSize = preferredSize
        }
        panel.add(cmbSegments)

        panel.add(Box.createHorizontalGlue())
        val rigidArea1 = Box.createRigidArea(scaledSize(10, 20))
        panel.add(rigidArea1)

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { e: ActionEvent? -> dispose() }
        panel.add(btnCancel)

        val rigidArea = Box.createRigidArea(scaledSize(10, 30))
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

            AppContext.downloader.addVideoDownload(
                videoId, name, picker.selectedCategory?.id, picker.targetFolder, cmbSegments.selectedItem as Int
            )
        } finally {
            dispose()
        }
    }

    /**
     * The storage line for the folder the file will be saved to. A stream's size is often unknown, in
     * which case the warning uses a fixed floor.
     */
    private fun updateStorageStatus() {
        if (!::storageStatus.isInitialized || !::picker.isInitialized) return
        storageStatus.update(picker.targetFolder, fileSize)
    }

    override fun dispose() {
        if (::picker.isInitialized) picker.dispose()
        super.dispose()
    }

    /** Packing first is what makes the window insets known, so the content is never clipped. */
    private fun adjustSize() {
        pack()
        size = Dimension(max(width, 500.px), max(height, 270.px))
        TempFolderCards.clampToScreen(this)
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
        cmbSegments.selectedItem = AppContext.config.maxSegments
        txtFileName.text = FileUtils.sanitizeFileName(fileName)
        lblFileInfo.text = fileSize?.let { FormatHelper.formatSize(it.toDouble()) } ?: "---"
        this.fileSize = fileSize
        val tracker = AppContext.videoTracker
        val http = tracker.getHttpVideo(vid)
        val stream = tracker.getHlsVideo(vid) ?: tracker.getDashVideo(vid)
        // A plain media file is written straight to its folder; HLS/DASH segments go to the temp folder first.
        storageStatus.usesTempFolder = http == null
        val url = http?.url ?: tracker.getHlsVideo(vid)?.url ?: tracker.getDashVideo(vid)?.url
        picker.reset(txtFileName.text, url, http?.origin ?: stream?.origin)
        updateStorageStatus()

        this.adjustSize()
        this.setLocationRelativeTo(null)
        this.isVisible = true
    }

    /** Picks the file-type glyph, the one the row will show. */
    private fun updateFileIcon() {
        val glyph = CategoryStyle.lineVariant(CategoryStyle.glyphForFile(txtFileName.text.orEmpty()))
        lblFileInfo.icon = createIcon(glyph, 36, Color.GRAY)
    }

    /** The name picks the icon and, until the user picks a category, re-runs the category rules. */
    private fun fileNameChanged() {
        updateFileIcon()
        if (::picker.isInitialized) picker.fileNameChanged(txtFileName.text)
        updateStorageStatus()
    }

    private fun attachFileNameChangeListener() {
        txtFileName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = fileNameChanged()

            override fun removeUpdate(e: DocumentEvent) = fileNameChanged()

            override fun changedUpdate(e: DocumentEvent) = fileNameChanged()
        })
    }
}
