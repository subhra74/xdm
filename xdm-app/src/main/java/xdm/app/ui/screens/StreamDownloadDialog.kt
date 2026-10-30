package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.CappedComboBox
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.screens.settings.count
import xdm.app.ui.screens.settings.segmentsSpinner
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.dialogHeight
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.core.downloaders.DashDownloadTaskInfo
import xdm.core.downloaders.HlsDownloadTaskInfo
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.network.http.HeaderMap
import xdm.core.util.CoreUtils
import xdm.core.util.FileUtils
import xdm.core.util.Logger
import xdm.integration.AudioChoice
import xdm.integration.AudioMode
import xdm.integration.FormatChoice
import xdm.integration.StreamChoices
import xdm.integration.StreamPick
import xdm.integration.VideoHelper
import xdm.integration.VideoHelper.LoadedManifest
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.SwingConstants
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * "Stream download (HLS/DASH)" from the main menu: a playlist or manifest URL typed or pasted in,
 * loaded with [VideoHelper.loadHls] / [VideoHelper.loadDash] (the same fetch and parsing as captured
 * streams), then a format from the list and an audio track from the drop-down. Headers and cookies
 * from the second tab go with the playlist request and every segment. Non-modal: several can be open.
 * An independent frame (docs/windows-and-modality.md): it may sit open while the user browses, so it
 * has its own taskbar button and stays when the main window goes to the tray. [near] is where it opens.
 *
 * Everything here runs on the EDT except the load, whose result is dropped when the inputs changed
 * in the meantime ([generation]).
 */
class StreamDownloadDialog(private val near: Window?) : JFrame() {
    private val radioHls = JRadioButton(text("SD_HLS"), true)
    private val radioDash = JRadioButton(text("SD_DASH"))
    // Columns: a long URL or file name must not widen the dialog; the row stretches the field.
    private val txtUrl = JTextField().apply { columns = 10 }
    private val btnPaste = JButton(createIcon(RemixIcon.CLIPBOARD_LINE, 16, Color.GRAY)).apply { toolTipText = text("SD_PASTE") }
    private val btnLoad = JButton(text("SD_LOAD"))
    private val lblStatus = JLabel(" ")
    private val lblFormat = JLabel(text("SD_FORMAT"))
    private val formatModel = DefaultListModel<FormatChoice>()
    private val lstFormat = JList(formatModel)
    private val scrollFormat = JScrollPane(lstFormat)

    // Takes the spare height while the format list is hidden, so the rows stay at the top
    // instead of GridBagLayout centring them.
    private val bottomFiller = JPanel().apply { isOpaque = false }
    private val lblAudio = JLabel(text("SD_AUDIO"))
    private val audioModel = DefaultComboBoxModel<AudioChoice>()
    private val cmbAudio = CappedComboBox(audioModel)

    /** Name and Value. Edited through Add / Edit (or a double-click), not in the cells. */
    private val headerModel = object : DefaultTableModel(arrayOf(text("SD_NAME"), text("SD_VALUE")), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val tblHeaders = JTable(headerModel)
    private val btnEditHeader = JButton(text("SD_EDIT"))
    private val btnRemoveHeader = JButton(text("SD_REMOVE"))
    private val tabs = JTabbedPane()

    private val txtFileName = JTextField().apply { columns = 10 }
    /** A stream URL entered here counts as a browser capture (it is a video): the category rules apply. */
    private val picker = DestinationPicker(this, capture = true)
    private val spnSegments = segmentsSpinner()
    private val btnDownload = JButton(text("ND_DOWNLOAD"))

    /** The manifest the current choices came from; null until a load for the current inputs succeeds. */
    private var loaded: LoadedManifest? = null

    /** Bumped by every change that makes a load stale; a load only applies if it is unchanged. */
    private var generation = 0
    private var cancelLoad = AtomicBoolean()

    /** Set once the user types a file name: from then on choices no longer replace it. */
    private var nameEdited = false
    private var settingName = false

    init {
        // Named for the class-list recording session (xdm.app.recording), which fills them in.
        txtUrl.name = "SD_URL"
        lstFormat.name = "SD_FORMAT"
        cmbAudio.name = "SD_AUDIO"
        // Rounded like the new-download window's fields.
        txtUrl.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        txtFileName.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        title = text("SD_TITLE")
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()

        tabs.addTab(text("SD_TAB_STREAM"), createStreamTab())
        tabs.addTab(text("SD_TAB_HEADERS"), createHeadersTab())
        contentPane.add(tabs, BorderLayout.CENTER)
        contentPane.add(createDownloadPanel(), BorderLayout.SOUTH)
        (contentPane as JComponent).border = ScaledEmptyBorder(10, 10, 10, 10)

        wireEvents()
        picker.reset(null, null, null)
        spnSegments.count = AppContext.config.maxSegments
        rootPane.defaultButton = btnLoad
        reset()

        // Like the batch dialog: 600 px tall, but no taller than the main window or the screen.
        val height = dialogHeight(DIALOG_HEIGHT)
        size = Dimension(DIALOG_WIDTH.px, height)
        minimumSize = Dimension(MIN_WIDTH.px, minOf(MIN_HEIGHT.px, height))
        setLocationRelativeTo(near)
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun createStreamTab(): JComponent {
        val p = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(10, 10, 10, 10) }
        fun gbc(x: Int, y: Int, w: Int = 1, weightX: Double = 0.0, fill: Int = GridBagConstraints.NONE) =
            GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = w; weightx = weightX; this.fill = fill
                anchor = GridBagConstraints.WEST; insets = scaledInsets(4, 4, 4, 4)
            }

        ButtonGroup().apply { add(radioHls); add(radioDash) }
        val types = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(radioHls)
            add(Box.createRigidArea(scaledSize(15, 0)))
            add(radioDash)
        }
        p.add(JLabel(text("SD_TYPE")), gbc(0, 0))
        p.add(types, gbc(1, 0, 3))

        p.add(JLabel(text("SD_URL")), gbc(0, 1))
        p.add(txtUrl, gbc(1, 1, 1, 1.0, GridBagConstraints.HORIZONTAL))
        p.add(btnPaste, gbc(2, 1))
        p.add(btnLoad, gbc(3, 1))
        p.add(lblStatus, gbc(1, 2, 3, 1.0, GridBagConstraints.HORIZONTAL))

        lstFormat.selectionMode = ListSelectionModel.SINGLE_SELECTION
        lstFormat.cellRenderer = FormatRenderer()
        lstFormat.visibleRowCount = 7
        p.add(lblFormat, gbc(0, 3).apply { anchor = GridBagConstraints.NORTHWEST })
        p.add(scrollFormat, gbc(1, 3, 3, 1.0, GridBagConstraints.BOTH).apply { weighty = 1.0 })
        p.add(lblAudio, gbc(0, 4))
        p.add(cmbAudio, gbc(1, 4, 3, 1.0, GridBagConstraints.HORIZONTAL))
        p.add(bottomFiller, gbc(0, 5, 4, 1.0, GridBagConstraints.BOTH).apply { weighty = 1.0; insets = Insets(0, 0, 0, 0) })
        return p
    }

    private fun createHeadersTab(): JComponent {
        tblHeaders.fillsViewportHeight = true
        tblHeaders.columnModel.getColumn(0).preferredWidth = 160.px
        tblHeaders.columnModel.getColumn(1).preferredWidth = 380.px
        tblHeaders.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && tblHeaders.rowAtPoint(e.point) >= 0) editHeader()
            }
        })
        tblHeaders.selectionModel.addListSelectionListener { updateHeaderButtons() }

        val btnAddHeader = JButton(text("SD_ADD")).apply { addActionListener { addHeader() } }
        btnEditHeader.addActionListener { editHeader() }
        btnRemoveHeader.addActionListener { removeSelectedRows() }
        val btnPasteHeaders = JButton(text("SD_PASTE_HEADERS")).apply { addActionListener { pasteHeaders() } }
        val buttons = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(btnAddHeader); add(Box.createRigidArea(scaledSize(5, 0)))
            add(btnEditHeader); add(Box.createRigidArea(scaledSize(5, 0)))
            add(btnRemoveHeader); add(Box.createHorizontalGlue())
            add(btnPasteHeaders)
        }
        updateHeaderButtons()
        val south = JPanel(BorderLayout(0, 6.px)).apply {
            border = ScaledEmptyBorder(8, 0, 0, 0)
            add(buttons, BorderLayout.NORTH)
            add(JLabel(text("SD_HEADERS_HINT")), BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = ScaledEmptyBorder(10, 10, 10, 10)
            add(JScrollPane(tblHeaders), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    private fun createDownloadPanel(): JComponent {
        // Side padding lines the rows up with the tab content above them.
        val p = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(10, 10, 0, 10) }
        fun gbc(x: Int, y: Int, w: Int = 1, weightX: Double = 0.0, fill: Int = GridBagConstraints.NONE) =
            GridBagConstraints().apply {
                gridx = x; gridy = y; gridwidth = w; weightx = weightX; this.fill = fill
                anchor = GridBagConstraints.WEST; insets = scaledInsets(4, 4, 4, 4)
            }
        // Same columns and insets as the picker's rows below, so the three fields line up.
        p.add(JLabel(text("ND_FILE")).apply { horizontalAlignment = SwingConstants.RIGHT }, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST; insets = scaledInsets(5, 0, 5, 10); gridx = 0; gridy = 0
        })
        p.add(txtFileName, GridBagConstraints().apply {
            gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = scaledInsets(5, 0, 5, 5); gridx = 1; gridy = 0
        })
        picker.addTo(p, firstRow = 1, leftInset = 0)

        val btnCancel = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }
        btnDownload.addActionListener { download() }
        sameWidth(btnDownload, btnCancel)
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = ScaledEmptyBorder(6, 0, 0, 0)
            add(JLabel(text("ND_SEGMENTS"))); add(Box.createRigidArea(scaledSize(10, 0)))
            add(spnSegments); add(Box.createHorizontalGlue())
            add(btnCancel); add(Box.createRigidArea(scaledSize(10, 0)))
            add(btnDownload)
        }
        p.add(row, gbc(0, 3, 5, 1.0, GridBagConstraints.HORIZONTAL))
        return p
    }

    // ---- behaviour ------------------------------------------------------------------------

    private fun wireEvents() {
        txtUrl.document.addDocumentListener(onChange {
            // Follow an unambiguous extension; the user can still pick the other type.
            val url = txtUrl.text.lowercase(Locale.ROOT)
            if (url.contains(".mpd") && !radioDash.isSelected) radioDash.isSelected = true
            if (url.contains(".m3u8") && !radioHls.isSelected) radioHls.isSelected = true
            reset()
        })
        radioHls.addActionListener { reset() }
        radioDash.addActionListener { reset() }
        btnLoad.addActionListener { load() }
        btnPaste.addActionListener { pasteUrl() }
        headerModel.addTableModelListener { reset() }
        lstFormat.addListSelectionListener { if (!it.valueIsAdjusting) formatSelected() }
        cmbAudio.addActionListener { suggestName(); updateDownloadButton() }
        txtFileName.document.addDocumentListener(onChange {
            if (!settingName) nameEdited = true
            picker.fileNameChanged(txtFileName.text)
            updateDownloadButton()
        })
    }

    /** The inputs changed: whatever was loaded (or is loading) no longer applies. */
    private fun reset() {
        generation++
        cancelLoad.set(true)
        loaded = null
        formatModel.clear()
        audioModel.removeAllElements()
        setChoicesVisible(format = false, audio = false)
        btnLoad.isEnabled = txtUrl.text.isNotBlank()
        rootPane.defaultButton = btnLoad
        lblStatus.text = text("SD_ENTER_URL")
        updateDownloadButton()
    }

    private fun load() {
        val url = txtUrl.text.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            lblStatus.text = text("SD_ERR_URL")
            return
        }
        reset()
        val gen = generation
        val cancelled = AtomicBoolean().also { cancelLoad = it }
        val dash = radioDash.isSelected
        val (headers, cookie) = collectHeaders()
        btnLoad.isEnabled = false
        lblStatus.text = text("SD_LOADING")
        Thread({
            val result = runCatching {
                if (dash) VideoHelper.loadDash(url, headers, cookie, cancelled)
                else VideoHelper.loadHls(url, headers, cookie, cancelled)
            }
            SwingUtilities.invokeLater {
                if (gen != generation || !isDisplayable) return@invokeLater
                btnLoad.isEnabled = true
                result.onSuccess { show(it) }.onFailure {
                    Logger.info("XDM", "Stream download: could not load $url: ${it.message}")
                    lblStatus.text = text("SD_ERR_LOAD").format(it.message ?: it.javaClass.simpleName)
                }
            }
        }, "stream-load").apply { isDaemon = true }.start()
    }

    private fun show(manifest: LoadedManifest) {
        val formats = StreamChoices.of(manifest)
        if (formats.isEmpty()) {
            lblStatus.text = text("SD_ERR_LOAD").format(text("SD_NOTHING"))
            return
        }
        loaded = manifest
        formats.forEach { formatModel.addElement(it) }
        lblStatus.text = summary(manifest, formats)
        // A media playlist has nothing to choose; everything else shows its list.
        setChoicesVisible(format = manifest !is LoadedManifest.HlsMedia, audio = false)
        lstFormat.selectedIndex = 0
        formatSelected()
        val (headers, _) = collectHeaders()
        val origin = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.firstOrNull()
        picker.reset(txtFileName.text, manifest.url, origin)
        rootPane.defaultButton = btnDownload
    }

    private fun summary(manifest: LoadedManifest, formats: List<FormatChoice>): String {
        val audioTracks = formats.firstOrNull { it.audioMode == AudioMode.CHOOSE }?.audio?.size ?: 0
        return when (manifest) {
            is LoadedManifest.HlsMedia -> {
                val pick = formats[0].audio[0].pick
                if (pick is StreamPick.Http) text("SD_STATUS_SINGLE")
                else text("SD_STATUS_MEDIA").format(
                    manifest.playlist.mediaSegments.size,
                    duration(manifest.playlist.mediaSegments.sumOf { it.duration })
                )
            }

            is LoadedManifest.HlsMaster -> text("SD_STATUS_MASTER").format(formats.size, audioTracks)
            is LoadedManifest.Dash -> text("SD_STATUS_DASH").format(formats.size, audioTracks)
        }
    }

    private fun formatSelected() {
        val format = lstFormat.selectedValue ?: return
        audioModel.removeAllElements()
        when (format.audioMode) {
            AudioMode.CHOOSE -> format.audio.forEach { audioModel.addElement(it) }
            // One fixed entry that says where the audio is; the pick is the format's own.
            AudioMode.INCLUDED -> audioModel.addElement(AudioChoice(text("SD_AUDIO_INCLUDED"), format.audio[0].pick))
            AudioMode.NONE -> audioModel.addElement(format.audio[0])
        }
        cmbAudio.isEnabled = format.audioMode == AudioMode.CHOOSE
        setChoicesVisible(
            format = scrollFormat.isVisible,
            audio = scrollFormat.isVisible && format.audioMode != AudioMode.NONE
        )
        if (audioModel.size > 0) cmbAudio.selectedIndex = 0
        suggestName()
        updateDownloadButton()
    }

    private fun setChoicesVisible(format: Boolean, audio: Boolean) {
        lblFormat.isVisible = format
        scrollFormat.isVisible = format
        bottomFiller.isVisible = !format
        lblAudio.isVisible = audio
        cmbAudio.isVisible = audio
        revalidate()
    }

    private fun selectedPick(): StreamPick? =
        if (loaded == null) null else (cmbAudio.selectedItem as? AudioChoice)?.pick

    private fun updateDownloadButton() {
        btnDownload.isEnabled = selectedPick() != null && txtFileName.text.isNotBlank()
    }

    /** A name from the URL ("master", "index" and the like are replaced by the folder or host name). */
    private fun suggestName() {
        if (nameEdited) return
        val pick = selectedPick() ?: return
        val ext = when (pick) {
            is StreamPick.Hls -> "mp4"
            is StreamPick.Dash -> pick.extension
            is StreamPick.Http -> pick.extension
        }
        val uri = txtUrl.text.trim().toHttpUrlOrNull()
        val segments = uri?.pathSegments?.filter { it.isNotBlank() }.orEmpty()
        val last = segments.lastOrNull()?.substringBeforeLast('.')
        val generic = setOf("master", "index", "playlist", "manifest", "chunklist", "prog_index", "stream", "main", "video")
        val base = when {
            last != null && last.lowercase(Locale.ROOT) !in generic -> last
            segments.size >= 2 -> segments[segments.size - 2]
            else -> uri?.host ?: "stream"
        }
        settingName = true
        try {
            txtFileName.text = FileUtils.sanitizeFileName("$base.$ext") ?: "stream.$ext"
        } finally {
            settingName = false
        }
    }

    private fun download() {
        val pick = selectedPick() ?: return
        val manifest = loaded ?: return
        val name = FileUtils.sanitizeFileName(txtFileName.text.trim())?.takeIf { it.isNotBlank() } ?: return
        val (headers, cookie) = collectHeaders()
        val origin = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.firstOrNull()
        val folder = picker.targetFolder
        val categoryId = picker.selectedCategory?.id
        val segments = spnSegments.count
        val config = AppContext.config
        val downloader = AppContext.downloader
        when (pick) {
            is StreamPick.Hls -> downloader.startHlsDownload(
                HlsDownloadTaskInfo(
                    id = CoreUtils.uniqueId(), fileName = name, tempDir = config.tempFolder, respectFileName = true,
                    cookie = cookie, headers = headers, origin = origin, categoryId = categoryId,
                    targetFolder = folder, maxPiece = segments,
                    authInfo = null, url = pick.url, audioUrl = pick.audioUrl, audioOnly = pick.audioOnly,
                    independent = pick.independent,
                )
            )

            is StreamPick.Dash -> downloader.startDashDownload(
                DashDownloadTaskInfo(
                    id = CoreUtils.uniqueId(), fileName = name, tempDir = config.tempFolder, respectFileName = true,
                    cookie = cookie, headers = headers, origin = origin, categoryId = categoryId,
                    targetFolder = folder, maxPiece = segments,
                    authInfo = null, videoSegments = pick.video.segments, audioSegments = pick.audio.segments,
                    url = manifest.url, audioMime = pick.audio.mimeType, videoMime = pick.video.mimeType,
                )
            )

            is StreamPick.Http -> downloader.startHttpDownload(
                HttpDownloadTaskInfo(
                    id = CoreUtils.uniqueId(), url = pick.url, fileName = name, respectFileName = true,
                    cookie = cookie, headers = headers, origin = origin, categoryId = categoryId,
                    targetFolder = folder, maxPiece = segments,
                    authInfo = null, knownFileSize = null,
                )
            )
        }
        dispose()
    }

    override fun dispose() {
        cancelLoad.set(true)
        picker.dispose()
        super.dispose()
    }

    // ---- URL and headers ------------------------------------------------------------------

    private fun clipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** Paste: the clipboard's first line as the URL, loaded at once when it names a playlist. */
    private fun pasteUrl() {
        val url = clipboardText()?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return
        txtUrl.text = url
        val lower = url.lowercase(Locale.ROOT)
        if ((lower.startsWith("http://") || lower.startsWith("https://")) && (lower.contains(".m3u8") || lower.contains(".mpd"))) {
            load()
        }
    }

    private fun addHeader() {
        val (name, value) = askHeader(text("SD_ADD"), "", "") ?: return
        headerModel.addRow(arrayOf<Any>(name, value))
        val row = headerModel.rowCount - 1
        tblHeaders.changeSelection(row, 0, false, false)
    }

    private fun editHeader() {
        val row = tblHeaders.selectedRow.takeIf { it >= 0 && tblHeaders.selectedRowCount == 1 } ?: return
        val (name, value) = askHeader(
            text("SD_EDIT"),
            headerModel.getValueAt(row, 0)?.toString().orEmpty(),
            headerModel.getValueAt(row, 1)?.toString().orEmpty(),
        ) ?: return
        headerModel.setValueAt(name, row, 0)
        headerModel.setValueAt(value, row, 1)
    }

    /** A small Name / Value dialog for Add and Edit; null when cancelled or left without a name. */
    private fun askHeader(title: String, name: String, value: String): Pair<String, String>? {
        val txtName = JTextField(name, 20).apply { this.name = "SD_HEADER_NAME" }
        val txtValue = JTextField(value, 32).apply { this.name = "SD_HEADER_VALUE" }
        listOf(txtName, txtValue).forEach { it.putClientProperty(FlatClientProperties.STYLE, "arc: 10") }
        val form = JPanel(GridBagLayout())
        fun gbc(x: Int, y: Int, weightX: Double = 0.0) = GridBagConstraints().apply {
            gridx = x; gridy = y; weightx = weightX; anchor = GridBagConstraints.WEST; insets = scaledInsets(4, 4, 4, 4)
            if (weightX > 0) fill = GridBagConstraints.HORIZONTAL
        }
        form.add(JLabel(text("SD_NAME")), gbc(0, 0))
        form.add(txtName, gbc(1, 0, 1.0))
        form.add(JLabel(text("SD_VALUE")), gbc(0, 1))
        form.add(txtValue, gbc(1, 1, 1.0))
        val result = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        val newName = txtName.text.trim()
        if (result != JOptionPane.OK_OPTION || newName.isEmpty()) return null
        return newName to txtValue.text.trim()
    }

    private fun updateHeaderButtons() {
        btnEditHeader.isEnabled = tblHeaders.selectedRowCount == 1
        btnRemoveHeader.isEnabled = tblHeaders.selectedRowCount > 0
    }

    private fun removeSelectedRows() {
        tblHeaders.selectedRows.sortedDescending().forEach { headerModel.removeRow(it) }
    }

    /** "Name: value" lines as browser dev tools copy them, one row each (a Cookie line included). */
    private fun pasteHeaders() {
        val clip = clipboardText() ?: return
        for ((name, value) in parseHeaderLines(clip)) headerModel.addRow(arrayOf<Any>(name, value))
    }

    /**
     * The table as request headers, with Cookie rows taken out as the download's cookie (several
     * are joined): the downloader sends cookies from there, not from the header map.
     */
    private fun collectHeaders(): Pair<HeaderMap, String?> {
        val headers = LinkedHashMap<String, MutableList<String>>()
        val cookies = ArrayList<String>()
        for (row in 0 until headerModel.rowCount) {
            val name = headerModel.getValueAt(row, 0)?.toString()?.trim().orEmpty()
            val value = headerModel.getValueAt(row, 1)?.toString()?.trim().orEmpty()
            if (name.isEmpty()) continue
            if (name.equals("Cookie", ignoreCase = true)) cookies.add(value)
            else headers.getOrPut(name) { ArrayList() }.add(value)
        }
        return headers to cookies.joinToString("; ").ifEmpty { null }
    }

    /** A Format entry: a 24 px film (or, for an audio stream, music) icon beside the label. */
    private class FormatRenderer : DefaultListCellRenderer() {
        private val videoIcon = createIcon(RemixIcon.FILM_LINE, 24, Color.GRAY)
        private val audioIcon = createIcon(RemixIcon.MUSIC_2_LINE, 24, Color.GRAY)

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            icon = if ((value as? FormatChoice)?.audioOnly == true) audioIcon else videoIcon
            iconTextGap = 10
            border = ScaledEmptyBorder(6, 8, 6, 8)
            return this
        }
    }

    private fun onChange(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    companion object {
        private const val DIALOG_WIDTH = 640
        private const val DIALOG_HEIGHT = 600
        private const val MIN_WIDTH = 520
        private const val MIN_HEIGHT = 460

        /** Headers a copied request carries that would break a download if replayed. */
        private val skippedHeaders = setOf(
            "host", "connection", "content-length", "range", "accept-encoding", "if-range",
            "if-none-match", "if-modified-since", "upgrade-insecure-requests", "te", "priority",
        )

        /**
         * Parses copied request headers: "Name: value" lines, also as curl `-H 'Name: value'`
         * arguments. HTTP/2 pseudo-headers (":authority") and headers that must not be replayed are
         * left out.
         */
        fun parseHeaderLines(text: String): List<Pair<String, String>> = text.lines().mapNotNull { raw ->
            var line = raw.trim().removeSuffix("\\").trim()
            if (line.startsWith("-H ") || line.startsWith("--header ")) line = line.substringAfter(' ').trim()
            line = line.trim('\'', '"').trim()
            if (line.isEmpty() || line.startsWith(":")) return@mapNotNull null
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val name = line.substring(0, idx).trim()
            if (name.contains(' ') || name.lowercase(Locale.ROOT) in skippedHeaders) return@mapNotNull null
            name to line.substring(idx + 1).trim()
        }

        /** 754.2 s -> "12:34"; an hour or more -> "1:02:34". */
        fun duration(seconds: Double): String {
            val total = seconds.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
