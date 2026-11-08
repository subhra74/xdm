package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.CategoryStyle
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.components.MessageBox
import xdm.app.ui.components.StorageStatusLabel
import xdm.app.ui.components.TempFolderCards
import xdm.app.ui.screens.settings.count
import xdm.app.ui.screens.settings.segmentsSpinner
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.capture.CapturedSubtitle
import xdm.capture.StreamingVideoDisplayInfo
import xdm.capture.VideoHelper
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.StreamingDownloadTaskInfo
import xdm.core.downloaders.SubtitleTrack
import xdm.core.util.*
import xdm.integration.EventChannel
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

/**
 * Truly ownerless (docs/windows-and-modality.md), not `JDialog()`: that hangs it on Swing's hidden
 * shared frame, which gives it no taskbar button on Windows. It must show while the main window is in
 * the tray, and on native Wayland, where always-on-top does nothing, the taskbar is how to find it.
 */
class NewVideoDownloadWindow : JDialog(null as Window?) {
    private lateinit var txtFileName: JTextField
    private lateinit var btnDownload: JButton
    private lateinit var picker: DestinationPicker
    private lateinit var lblFileInfo: JLabel
    private lateinit var spnSegments: JSpinner
    private lateinit var lblSubtitles: JLabel
    private lateinit var cmbSubtitles: JComboBox<SubtitleChoice>
    private lateinit var chkSaveAsSrt: JCheckBox
    /** The captured subtitle file this dialog downloads; null for a video. */
    private var capturedSubtitle: CapturedSubtitle? = null
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
        iconImages = windowIconImages
        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MIN_VALUE)
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, Double.MIN_VALUE)
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

        txtFileName = JTextField().apply { putClientProperty(FlatClientProperties.STYLE, "arc: 10") }
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

        lblFileInfo = object : JLabel() {
            /** The "---" width plus some room, set once below. */
            var minInfoWidth = 0

            // Never narrower than its text: a fixed width cut a stream size ("~ 123.4 MB") short.
            override fun getPreferredSize(): Dimension =
                super.getPreferredSize().let { Dimension(max(it.width, minInfoWidth), it.height) }
        }.apply {
            icon = createIcon(RemixIcon.FILE_FILL, 36, Color.GRAY)
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalTextPosition = SwingConstants.CENTER
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            text = "---"
            minInfoWidth = preferredSize.width + 30.px
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

        // Hidden unless the stream has subtitles (showWindow).
        lblSubtitles = JLabel(text("ND_SUBTITLES")).apply { horizontalAlignment = SwingConstants.RIGHT }
        contentPane.add(lblSubtitles, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = scaledInsets(5, 20, 5, 10)
            gridx = 0
            gridy = 4
        })
        cmbSubtitles = JComboBox<SubtitleChoice>().apply { name = "ND_SUBTITLES" }
        contentPane.add(cmbSubtitles, GridBagConstraints().apply {
            gridwidth = 4
            anchor = GridBagConstraints.WEST
            insets = scaledInsets(5, 0, 5, 5)
            gridx = 1
            gridy = 4
        })
        // Same cell as the subtitle dropdown: a video shows that, a captured subtitle file this.
        chkSaveAsSrt = JCheckBox(text("ND_SAVE_AS_SRT")).apply {
            name = "ND_SAVE_AS_SRT"
            isOpaque = false
            isVisible = false
            addActionListener { srtChoiceChanged() }
        }
        contentPane.add(chkSaveAsSrt, GridBagConstraints().apply {
            gridwidth = 4
            anchor = GridBagConstraints.WEST
            insets = scaledInsets(5, 0, 5, 5)
            gridx = 1
            gridy = 4
        })

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
            gridy = 5
        }
        contentPane.add(infoBox, gbcInfoBox)

        val panel = JPanel()
        panel.border = ScaledEmptyBorder(10, 15, 10, 15)
        val gcPanel = GridBagConstraints().apply {
            weightx = 1.0
            gridwidth = 6
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 6
        }
        contentPane.add(panel, gcPanel)
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

//        val btnQueue = JButton(text("ND_QUEUE"))
//        panel.add(btnQueue)

        panel.add(JLabel(text("ND_SEGMENTS")))
        panel.add(Box.createRigidArea(scaledSize(10, 30)))

        spnSegments = segmentsSpinner().apply { count = AppContext.config.maxSegments }
        panel.add(spnSegments)

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
                private var focused = false

                /** First activation only; see [NewDownloadWindow] (KDE closes combo popups otherwise). */
                override fun windowActivated(e: WindowEvent) {
                    if (focused) return
                    focused = true
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
            MessageBox.show(this, null, text("MSG_NO_FILE"))
            return
        }

        try {
            val name = FileUtils.sanitizeFileName(file)!!
            //TODO: Check FFmpeg required

            val convertToSrt = chkSaveAsSrt.isVisible && chkSaveAsSrt.isSelected
            if (chkSaveAsSrt.isVisible && AppContext.config.saveSubtitlesAsSrt != convertToSrt) {
                AppContext.config.saveSubtitlesAsSrt = convertToSrt
                AppContext.config.save()
            }
            AppContext.downloader.addVideoDownload(
                videoId, name, picker.selectedCategory?.id, picker.targetFolder, spnSegments.count,
                (cmbSubtitles.selectedItem as? SubtitleChoice)?.track, convertToSrt,
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
            ?: tracker.getSubtitle(videoId)?.task?.url
            ?: tracker.getHlsVideo(videoId)?.url
            ?: tracker.getDashVideo(videoId)?.url
        val host = url?.toHttpUrlOrNull()?.host
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

    /** The size ("≈ 245.3 MB"), else the resolution, else just the kind of stream. */
    private fun streamInfo(display: StreamingVideoDisplayInfo?, stream: Any): String =
        display?.streamSummary ?: if (stream is DashDownloadTaskInfo) "DASH" else "HLS"

    fun showWindow(vid: Long, fileName: String, fileSize: Long?, contentType: String?) {
        this.videoId = vid
        spnSegments.count = AppContext.config.maxSegments
        txtFileName.text = FileUtils.sanitizeFileName(fileName)
        val tracker = AppContext.videoTracker
        val http = tracker.getHttpVideo(vid)
        val stream = tracker.getHlsVideo(vid) ?: tracker.getDashVideo(vid)
        val subtitle = tracker.getSubtitle(vid)
        capturedSubtitle = subtitle
        val display = tracker.getDisplayInfo(vid)
        // A stream's size comes from its playlists (an estimate, unless byte ranges give it exactly).
        this.fileSize = if (http == null && stream != null) display?.streamSize?.neededBytes else fileSize
        lblFileInfo.text = when {
            http == null && stream != null -> streamInfo(display, stream)
            else -> fileSize?.let { FormatHelper.formatSize(it.toDouble()) } ?: "---"
        }
        if (http == null && stream != null && display?.streamSize == null) {
            // No player loaded a variant of this master: load it once now for the size.
            VideoHelper.sizeCapturedStream(vid) { updated ->
                SwingUtilities.invokeLater {
                    if (videoId != vid || !isDisplayable) return@invokeLater
                    lblFileInfo.text = streamInfo(updated, stream)
                    this.fileSize = updated.streamSize?.neededBytes
                    updateStorageStatus()
                    // setText revalidates: the layout gives the label its new preferred width and the
                    // stretching fields beside it give up the room; the window keeps its size.
                }
            }
        }
        // A plain media file is written straight to its folder; HLS/DASH segments go to the temp folder first.
        storageStatus.usesTempFolder = http == null && subtitle == null
        // A subtitle file is a few KB fetched in one piece: the segment count does not apply.
        spnSegments.isEnabled = subtitle == null
        val convertible = subtitle?.format?.convertible == true
        chkSaveAsSrt.isVisible = convertible
        if (convertible) {
            chkSaveAsSrt.isSelected = AppContext.config.saveSubtitlesAsSrt
            srtChoiceChanged()
        }
        val url = http?.url ?: subtitle?.task?.url ?: tracker.getHlsVideo(vid)?.url ?: tracker.getDashVideo(vid)?.url
        picker.reset(txtFileName.text, url, http?.origin ?: subtitle?.task?.origin ?: stream?.origin)
        updateStorageStatus()
        fillSubtitles(
            when (stream) {
                is HlsDownloadTaskInfo -> stream.subtitles
                is DashDownloadTaskInfo -> stream.subtitles
                else -> emptyList()
            }
        )

        this.adjustSize()
        this.setLocationRelativeTo(null)
        this.isVisible = true
    }

    /**
     * Swaps the name's extension between the subtitle's own and `.srt` as "Save as SRT" changes, so
     * the name shown is the name saved. A name the user typed another extension into is left alone.
     */
    private fun srtChoiceChanged() {
        val format = capturedSubtitle?.format ?: return
        val name = txtFileName.text
        val (from, to) = if (chkSaveAsSrt.isSelected) format.ext to "srt" else "srt" to format.ext
        if (name.endsWith(".$from", ignoreCase = true)) {
            txtFileName.text = name.dropLast(from.length) + to
        }
    }

    /** One dropdown entry; a null [track] is None. */
    private class SubtitleChoice(val track: SubtitleTrack?, private val label: String) {
        override fun toString() = label
    }

    /**
     * None plus each track. Preselects the system language (a full track before a forced one), else
     * the track the manifest marks default, else None. The row is hidden when there is nothing to pick.
     */
    private fun fillSubtitles(tracks: List<SubtitleTrack>) {
        val choices = listOf(SubtitleChoice(null, text("ND_SUBTITLES_NONE"))) +
            tracks.map { SubtitleChoice(it, subtitleLabel(it)) }
        cmbSubtitles.model = DefaultComboBoxModel(choices.toTypedArray())
        val system = Locale.getDefault().language
        val pick = tracks.sortedBy { it.forced }.firstOrNull { sameLanguage(it.language, system) }
            ?: tracks.firstOrNull { it.isDefault && !it.forced }
        cmbSubtitles.selectedIndex = pick?.let { tracks.indexOf(it) + 1 } ?: 0
        lblSubtitles.isVisible = tracks.isNotEmpty()
        cmbSubtitles.isVisible = tracks.isNotEmpty()
    }

    private fun sameLanguage(tag: String, language: String): Boolean =
        tag.isNotBlank() && Locale.forLanguageTag(tag).language.equals(language, ignoreCase = true)

    /** The manifest's name for the track, else its language's name in the UI language. */
    private fun subtitleLabel(track: SubtitleTrack): String {
        val base = track.name.ifBlank {
            Locale.forLanguageTag(track.language).displayName.ifBlank { track.language.ifBlank { "?" } }
        }
        return if (track.forced) text("ND_SUBTITLE_FORCED").format(base) else base
    }

    /** Picks the file-type glyph, the one the row will show. */
    private fun updateFileIcon() {
        val glyph = CategoryStyle.glyphForFile(txtFileName.text.orEmpty())
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
