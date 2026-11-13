package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.DestinationPicker
import xdm.app.ui.screens.settings.count
import xdm.app.ui.screens.settings.segmentsSpinner
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.dialogHeight
import xdm.app.utils.px
import xdm.app.utils.sameWidth
import xdm.app.utils.scaledInsets
import xdm.app.utils.scaledSize
import xdm.app.utils.windowIconImages
import xdm.app.ytdlp.ExternalTool
import xdm.app.ytdlp.ExternalTools
import xdm.app.ytdlp.FoundTool
import xdm.app.ytdlp.InstalledTool
import xdm.app.ytdlp.OkToolHttp
import xdm.app.ytdlp.SaveAs
import xdm.app.ytdlp.ToolInstallException
import xdm.app.ytdlp.ToolInstaller
import xdm.app.ytdlp.ToolOrigin
import xdm.app.ytdlp.ToolRelease
import xdm.app.ytdlp.VideoChoice
import xdm.app.ytdlp.YtDlp
import xdm.app.ytdlp.YtDlpChoices
import xdm.app.ytdlp.YtDlpException
import xdm.app.ytdlp.YtDlpTask
import xdm.app.ytdlp.YtDlpTasks
import xdm.app.ytdlp.YtFormat
import xdm.app.ytdlp.YtInfo
import xdm.core.network.http.Range
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.FileUtils
import xdm.core.util.FormatHelper
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Video download" from the main menu: a page URL is resolved by yt-dlp (`-J`, nothing downloaded by
 * it), a quality is picked, and XDM downloads the formats with its own engine and merges them with
 * the built-in muxer (or ffmpeg, when set in Settings). See private-docs/docs/ytdlp-download.md.
 *
 * Three pages:
 * 1. Tools: each tool's path and status. Missing ones can be installed into XDM's `tools` folder or
 *    pointed to with Browse. Skipped when every tool is found.
 * 2. Page URL and the extra yt-dlp arguments, with the tool versions and the update check below.
 *    This is the only place tools are checked for updates and updated.
 * 3. Formats: a progress bar while yt-dlp runs, then the qualities and where to save.
 *
 * No site is named anywhere in this dialog. An independent frame like the stream dialog
 * (docs/windows-and-modality.md). Everything runs on the EDT except yt-dlp, the size probe and the
 * tool work, whose results are dropped when the inputs changed meanwhile ([generation]) or the
 * dialog closed.
 */
class VideoDownloadDialog(private val near: Window?) : JFrame() {
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)

    // Page 1: tools.
    private val toolRows = ExternalTool.entries.associateWith { ToolRow(it) }
    private val lblInstall = JLabel(" ")
    private val btnInstallMissing = JButton(text("VD_INSTALL_MISSING"))
    private val btnInstallAll = JButton(text("VD_INSTALL_ALL"))
    private val btnNext = JButton(text("VD_NEXT"))

    // Page 2: URL.
    private val txtUrl = JTextField().apply { columns = 10 }
    private val btnPaste = JButton(createIcon(RemixIcon.CLIPBOARD_LINE, 16, Color.GRAY)).apply { toolTipText = text("SD_PASTE") }
    private val txtExtraArgs = JTextField(AppContext.config.ytDlpExtraArgs).apply { columns = 10 }
    private val lblUrlStatus = JLabel(" ")
    private val btnGet = JButton(text("VD_GET"))
    private val lblVersions = JLabel(" ")
    private val lblUpdates = JLabel(" ")
    private val btnToolsPage = JButton(text("VD_TOOLS"))
    private val btnCheck = JButton(text("VD_CHECK_UPDATES"))
    private val btnUpdate = JButton(text("VD_UPDATE")).apply { isVisible = false }

    // Page 3: formats.
    private val formatCards = CardLayout()
    private val formatPanel = JPanel(formatCards)
    private val lblLoading = JLabel(" ")
    private val progress = JProgressBar().apply { isIndeterminate = true }
    private val btnUpdateRetry = JButton(text("VD_UPDATE_RETRY")).apply { isVisible = false }
    private val lblFound = JLabel(" ")
    private val choiceModel = DefaultListModel<VideoChoice>()
    private val lstChoices = JList(choiceModel)
    private val txtFileName = JTextField().apply { columns = 10 }
    private val picker = DestinationPicker(this, capture = true)
    private val spnSegments = segmentsSpinner()

    /** Segments beside Back, shown with the results. */
    private val segmentsBox = JPanel().apply {
        isOpaque = false
        isVisible = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(Box.createRigidArea(scaledSize(10, 0)))
        add(JLabel(text("ND_SEGMENTS")))
        add(Box.createRigidArea(scaledSize(8, 0)))
        add(spnSegments.apply { maximumSize = preferredSize })
    }
    private val btnBack = JButton(text("CS_BACK"))
    private val btnDownload = JButton(text("ND_DOWNLOAD"))

    private var info: YtInfo? = null
    private var generation = 0
    private var cancel = AtomicBoolean()
    private val ytProcess = AtomicReference<Process?>()
    private var nameEdited = false
    private var settingName = false

    /** The tools as last looked up; null until the first lookup is done. */
    private var found: Map<ExternalTool, FoundTool?>? = null
    private var versions: Map<ExternalTool, String?> = emptyMap()
    private var latest: Map<ExternalTool, ToolRelease> = emptyMap()
    private var installedTools: Map<ExternalTool, InstalledTool> = emptyMap()
    private var installing = false
    private var lookingUp = false
    private val closed = AtomicBoolean()

    /** Leave the tools page by itself once every tool is found; off once the user came back to it. */
    private var autoAdvance = true
    private var page = PAGE_TOOLS

    private inner class ToolRow(val tool: ExternalTool) {
        val name = JLabel(tool.id)
        val path = JTextField().apply {
            columns = 10
            name = "VD_PATH_${tool.name}"
            putClientProperty(FlatClientProperties.STYLE, "arc: 10")
            putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ExternalTools.managedFile(tool).absolutePath)
        }
        val browse = JButton(text("BTN_BROWSE"))
        val status = JLabel(" ").apply { foreground = muted() }

        /**
         * The field and Browse, or "Checking…" in their place while the tool is looked up. Both cards
         * are one field high, so a row does not jump when it switches.
         */
        private val states = CardLayout()
        val slot = JPanel(states).apply {
            isOpaque = false
            add(JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(JLabel(text("VD_CHECKING")).apply { foreground = muted() }, GridBagConstraints().apply {
                    gridx = 0; gridy = 0; weightx = 1.0; anchor = GridBagConstraints.WEST; insets = scaledInsets(0, 2, 0, 0)
                })
            }, ROW_CHECKING)
            add(JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(path, GridBagConstraints().apply {
                    gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                })
                add(browse, GridBagConstraints().apply { gridx = 1; gridy = 0; insets = scaledInsets(0, 8, 0, 0) })
            }, ROW_READY)
        }

        fun checking(on: Boolean) {
            states.show(slot, if (on) ROW_CHECKING else ROW_READY)
            if (on) status.text = " "
        }
    }

    init {
        txtUrl.name = "VD_URL"
        lstChoices.name = "VD_QUALITY"
        txtUrl.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        txtFileName.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        txtExtraArgs.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        title = text("VD_TITLE")
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        cardPanel.add(createToolsPage(), PAGE_TOOLS)
        cardPanel.add(createUrlPage(), PAGE_URL)
        cardPanel.add(createFormatsPage(), PAGE_FORMATS)
        contentPane.add(cardPanel, BorderLayout.CENTER)
        (contentPane as JComponent).border = ScaledEmptyBorder(10, 10, 10, 10)

        wireEvents()
        picker.reset(null, null, null)
        spnSegments.count = AppContext.config.maxSegments
        clipboardUrl()?.let { txtUrl.text = it }
        lblUrlStatus.text = text("VD_ENTER_URL")
        showPage(PAGE_TOOLS)

        val height = dialogHeight(DIALOG_HEIGHT)
        size = Dimension(DIALOG_WIDTH.px, height)
        minimumSize = Dimension(MIN_WIDTH.px, minOf(MIN_HEIGHT.px, height))
        setLocationRelativeTo(near)
        refreshTools()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun gbc(x: Int, y: Int, w: Int = 1, weightX: Double = 0.0, fill: Int = GridBagConstraints.NONE) =
        GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = w; weightx = weightX; this.fill = fill
            anchor = GridBagConstraints.WEST; insets = scaledInsets(4, 4, 4, 4)
        }

    private fun filler(y: Int, w: Int) =
        gbc(0, y, w, 1.0, GridBagConstraints.BOTH).apply { weighty = 1.0 }

    private fun buttonRow(vararg items: Component?): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = ScaledEmptyBorder(6, 0, 0, 0)
        items.forEachIndexed { i, c ->
            if (c == null) add(Box.createHorizontalGlue())
            else {
                if (i > 0 && items[i - 1] != null) add(Box.createRigidArea(scaledSize(10, 0)))
                add(c)
            }
        }
    }

    private fun cancelButton() = JButton(text("ND_CANCEL")).apply { addActionListener { dispose() } }

    private fun createToolsPage(): JComponent {
        val p = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(4, 4, 4, 4) }
        p.add(wrappingText(text("VD_MISSING")), GridBagConstraints().apply {
            gridx = 0; gridy = 0; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = scaledInsets(0, 0, 14, 0)
        })
        // Two grid rows per tool: name and field on the first, the status under the field.
        ExternalTool.entries.forEachIndexed { i, tool ->
            val r = toolRows.getValue(tool)
            val y = 1 + i * 2
            p.add(r.name, GridBagConstraints().apply {
                gridx = 0; gridy = y; anchor = GridBagConstraints.EAST; insets = scaledInsets(0, 0, 0, 12)
            })
            p.add(r.slot, GridBagConstraints().apply {
                gridx = 1; gridy = y; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            })
            p.add(r.status, GridBagConstraints().apply {
                gridx = 1; gridy = y + 1; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = scaledInsets(4, 2, 14, 0)
            })
        }
        val n = 1 + ExternalTool.entries.size * 2
        p.add(lblInstall, GridBagConstraints().apply {
            gridx = 0; gridy = n; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        p.add(JPanel().apply { isOpaque = false }, filler(n + 1, 2))
        val btnCancel = cancelButton()
        sameWidth(btnNext, btnCancel)
        p.add(buttonRow(btnInstallMissing, btnInstallAll, null, btnCancel, btnNext), GridBagConstraints().apply {
            gridx = 0; gridy = n + 2; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
        return p
    }

    private fun createUrlPage(): JComponent {
        val p = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(4, 4, 4, 4) }
        // Labels above their fields, left-aligned with them.
        val label = { y: Int, top: Int -> gbc(0, y, 2, 1.0, GridBagConstraints.HORIZONTAL).apply { insets = scaledInsets(top, 4, 4, 4) } }
        p.add(JLabel(text("VD_URL")), label(0, 0))
        p.add(txtUrl, gbc(0, 1, 1, 1.0, GridBagConstraints.HORIZONTAL))
        p.add(btnPaste, gbc(1, 1))
        p.add(JLabel(text("VD_EXTRA_ARGS")), label(2, 12))
        p.add(txtExtraArgs, gbc(0, 3, 2, 1.0, GridBagConstraints.HORIZONTAL))
        lblUrlStatus.foreground = muted()
        p.add(lblUrlStatus, gbc(0, 4, 2, 1.0, GridBagConstraints.HORIZONTAL))
        val btnCancel = cancelButton()
        sameWidth(btnGet, btnCancel)
        p.add(buttonRow(null, btnCancel, btnGet), gbc(0, 5, 2, 1.0, GridBagConstraints.HORIZONTAL))
        p.add(JPanel().apply { isOpaque = false }, filler(6, 2))

        p.add(JSeparator(), gbc(0, 7, 2, 1.0, GridBagConstraints.HORIZONTAL))
        lblVersions.foreground = muted()
        p.add(lblUpdates, gbc(0, 8, 2, 1.0, GridBagConstraints.HORIZONTAL))
        p.add(buttonRow(btnToolsPage, lblVersions, null, btnCheck, btnUpdate), gbc(0, 9, 2, 1.0, GridBagConstraints.HORIZONTAL))
        return p
    }

    private fun createFormatsPage(): JComponent {
        val p = JPanel(BorderLayout()).apply { border = ScaledEmptyBorder(4, 4, 4, 4) }

        // Message, 100 px bar and the retry button stacked in the middle of the page.
        val loading = JPanel(GridBagLayout())
        lblLoading.horizontalAlignment = SwingConstants.CENTER
        loading.add(lblLoading, GridBagConstraints().apply { gridx = 0; gridy = 0; insets = scaledInsets(0, 0, 10, 0) })
        progress.preferredSize = Dimension(100.px, 4.px)
        loading.add(progress, GridBagConstraints().apply { gridx = 0; gridy = 1 })
        loading.add(btnUpdateRetry, GridBagConstraints().apply { gridx = 0; gridy = 2; insets = scaledInsets(14, 0, 0, 0) })
        formatPanel.add(loading, FORMATS_LOADING)

        // One grid, so Quality, File, Category and Save to share a right-aligned label column.
        val results = JPanel(GridBagLayout())
        results.add(lblFound, GridBagConstraints().apply {
            gridx = 0; gridy = 0; gridwidth = 5; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST; insets = scaledInsets(0, 0, 8, 0)
        })
        lstChoices.selectionMode = ListSelectionModel.SINGLE_SELECTION
        lstChoices.cellRenderer = ChoiceRenderer()
        lstChoices.visibleRowCount = 7
        results.add(JLabel(text("VD_QUALITY")), GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTHEAST; insets = scaledInsets(5, 0, 5, 10); gridx = 0; gridy = 1
        })
        results.add(JScrollPane(lstChoices), GridBagConstraints().apply {
            gridx = 1; gridy = 1; gridwidth = 4; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
            insets = scaledInsets(5, 0, 5, 5)
        })
        results.add(JLabel(text("ND_FILE")).apply { horizontalAlignment = SwingConstants.RIGHT }, GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST; insets = scaledInsets(5, 0, 5, 10); gridx = 0; gridy = 2
        })
        results.add(txtFileName, GridBagConstraints().apply {
            gridwidth = 4; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
            insets = scaledInsets(5, 0, 5, 5); gridx = 1; gridy = 2
        })
        picker.addTo(results, firstRow = 3, leftInset = 0)
        formatPanel.add(results, FORMATS_RESULTS)

        p.add(formatPanel, BorderLayout.CENTER)
        val btnCancel = cancelButton()
        sameWidth(btnDownload, btnCancel, btnBack)
        p.add(buttonRow(btnBack, segmentsBox, null, btnCancel, btnDownload), BorderLayout.SOUTH)
        return p
    }

    // ---- behaviour ------------------------------------------------------------------------

    private fun wireEvents() {
        btnInstallMissing.addActionListener { install(ExternalTool.entries.filter { found?.get(it) == null }) }
        btnInstallAll.addActionListener { install(ExternalTool.entries) }
        btnNext.addActionListener { showPage(PAGE_URL) }
        toolRows.values.forEach { r ->
            r.browse.addActionListener { browse(r.tool) }
            r.path.addActionListener { pathEdited(r.tool) }
            r.path.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) = pathEdited(r.tool)
            })
        }

        txtUrl.document.addDocumentListener(onChange { urlChanged() })
        btnPaste.addActionListener { clipboardUrl()?.let { txtUrl.text = it; resolve() } }
        txtExtraArgs.document.addDocumentListener(onChange {
            AppContext.config.ytDlpExtraArgs = txtExtraArgs.text.trim()
        })
        btnGet.addActionListener { resolve() }
        btnToolsPage.addActionListener { autoAdvance = false; showPage(PAGE_TOOLS) }
        btnCheck.addActionListener { checkUpdates() }
        btnUpdate.addActionListener { install(ExternalTool.entries.filter { canUpdate(it) }) }

        btnUpdateRetry.addActionListener { install(listOf(ExternalTool.YT_DLP), thenResolve = true) }
        lstChoices.addListSelectionListener { if (!it.valueIsAdjusting) choiceSelected() }
        txtFileName.document.addDocumentListener(onChange {
            if (!settingName) nameEdited = true
            picker.fileNameChanged(txtFileName.text)
            updateDownloadButton()
        })
        btnBack.addActionListener { stopResolving(); showPage(PAGE_URL) }
        btnDownload.addActionListener { download() }
    }

    private fun showPage(name: String) {
        page = name
        cards.show(cardPanel, name)
        rootPane.defaultButton = when (name) {
            PAGE_TOOLS -> btnNext
            PAGE_URL -> btnGet
            else -> btnDownload
        }
        updateButtons()
        if (name == PAGE_URL) SwingUtilities.invokeLater { txtUrl.requestFocusInWindow() }
    }

    private fun urlChanged() {
        lblUrlStatus.text = text("VD_ENTER_URL")
        updateButtons()
    }

    /** Whatever yt-dlp is doing for page 3 is no longer wanted. */
    private fun stopResolving() {
        generation++
        cancel.set(true)
        ytProcess.get()?.destroy()
        info = null
        choiceModel.clear()
        updateDownloadButton()
    }

    private fun toolsReady() = found?.values?.all { it != null } == true && !installing && !lookingUp

    private fun updateButtons() {
        val busy = installing || lookingUp
        btnInstallMissing.isEnabled = !busy && found?.values?.any { it == null } == true
        btnInstallAll.isEnabled = !busy
        btnNext.isEnabled = toolsReady()
        toolRows.values.forEach { it.browse.isEnabled = !busy; it.path.isEnabled = !busy }
        btnGet.isEnabled = txtUrl.text.isNotBlank() && toolsReady()
        btnToolsPage.isEnabled = !installing
        btnCheck.isEnabled = !busy
        btnUpdate.isVisible = ExternalTool.entries.any { canUpdate(it) }
        btnUpdate.isEnabled = !busy
        btnUpdateRetry.isEnabled = !busy
    }

    private fun resolve() {
        val url = txtUrl.text.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            lblUrlStatus.text = text("SD_ERR_URL")
            return
        }
        if (!toolsReady()) return
        stopResolving()
        val gen = generation
        val cancelled = AtomicBoolean().also { cancel = it }
        lblLoading.text = text("VD_LOADING")
        lblLoading.toolTipText = null
        progress.isVisible = true
        btnUpdateRetry.isVisible = false
        showFormats(false)
        showPage(PAGE_FORMATS)
        Thread({
            val result = runCatching { YtDlp.resolve(url, cancelled, processRef = ytProcess) }
            SwingUtilities.invokeLater {
                if (gen != generation || !isDisplayable) return@invokeLater
                result.onSuccess { show(it) }.onFailure { showError(it) }
            }
        }, "yt-dlp-resolve").apply { isDaemon = true }.start()
    }

    private fun showFormats(results: Boolean) {
        formatCards.show(formatPanel, if (results) FORMATS_RESULTS else FORMATS_LOADING)
        segmentsBox.isVisible = results
    }

    /** A message where the progress bar was, with the Back button to try another address. */
    private fun showProblem(vararg lines: String) {
        progress.isVisible = false
        lblLoading.text = "<html><div style='width: 420px; text-align: center'>" +
            lines.joinToString("<br><br>") { escape(it) } + "</div></html>"
        showFormats(false)
        rootPane.defaultButton = btnBack
    }

    private fun showError(e: Throwable) {
        val yt = e as? YtDlpException
        val reason = yt?.reason ?: e.message ?: e.javaClass.simpleName
        Logger.info("ytdlp", "Video download: $reason")
        val unsupported = yt?.unsupportedUrl == true
        val message = if (unsupported) text("VD_UNSUPPORTED") else text("VD_ERR").format(reason)
        showProblem(message)
        btnUpdateRetry.isVisible = false
        if (yt != null && (unsupported || yt.looksOutdated) && found?.get(ExternalTool.YT_DLP)?.origin == ToolOrigin.MANAGED) {
            offerUpdate(message, unsupported)
        }
        updateButtons()
    }

    /**
     * Shows Update and retry only when a newer yt-dlp than the installed one exists; otherwise says it
     * is already the latest. If the latest release cannot be read (offline, GitHub's rate limit), a
     * failure that looks like an outdated yt-dlp still offers the update, as before; an unsupported
     * site does not, as a newer yt-dlp only sometimes adds one.
     */
    private fun offerUpdate(message: String, unsupported: Boolean) {
        val gen = generation
        fun decide(known: Boolean) {
            if (gen != generation || !isDisplayable || page != PAGE_FORMATS) return
            val newer = canUpdate(ExternalTool.YT_DLP)
            btnUpdateRetry.isVisible = newer || (!known && !unsupported)
            if (known && !newer && !unsupported) {
                val v = versions[ExternalTool.YT_DLP] ?: latest.getValue(ExternalTool.YT_DLP).label
                showProblem(message, text("VD_YTDLP_LATEST").format(v))
            }
            updateButtons()
        }
        if (latest[ExternalTool.YT_DLP] != null) return decide(true)
        Thread({
            val release = runCatching { OkToolHttp().use { ToolInstaller.latest(ExternalTool.YT_DLP, it) } }
            val installed = ToolInstaller.installed()
            SwingUtilities.invokeLater {
                if (!isDisplayable) return@invokeLater
                release.onSuccess { latest = latest + (ExternalTool.YT_DLP to it); installedTools = installed }
                    .onFailure { Logger.info("ytdlp", "Could not read the latest yt-dlp release: ${it.message}") }
                decide(release.isSuccess)
            }
        }, "yt-dlp-latest").apply { isDaemon = true }.start()
    }

    private fun show(resolved: YtInfo) {
        if (resolved.isLive) return showProblem(text("VD_LIVE"))
        val choices = YtDlpChoices.of(resolved)
        if (choices.isEmpty()) return showProblem(text("VD_NONE"))
        info = resolved
        choices.forEach { choiceModel.addElement(it) }
        lblFound.text = text("VD_FOUND").format(resolved.title, choices.size)
        lblFound.toolTipText = resolved.title
        showFormats(true)
        lstChoices.selectedIndex = 0
        picker.reset(txtFileName.text, resolved.webpageUrl, resolved.webpageUrl)
        rootPane.defaultButton = btnDownload
        choiceSelected()
    }

    private fun choiceSelected() {
        val choice = lstChoices.selectedValue ?: return
        val i = info ?: return
        settingName = true
        try {
            txtFileName.text = if (!nameEdited) YtDlpChoices.fileName(i, choice)
            // Keep the user's name, with the extension of the container the choice is saved as.
            else txtFileName.text.substringBeforeLast('.', txtFileName.text) + "." + choice.container
        } finally {
            settingName = false
        }
        updateDownloadButton()
    }

    private fun updateDownloadButton() {
        btnDownload.isEnabled = info != null && lstChoices.selectedValue != null && txtFileName.text.isNotBlank()
    }

    private fun download() {
        val resolved = info ?: return
        val choice = lstChoices.selectedValue ?: return
        val name = FileUtils.sanitizeFileName(txtFileName.text.trim())?.takeIf { it.isNotBlank() } ?: return
        val save = SaveAs(name, picker.targetFolder, picker.selectedCategory?.id, spnSegments.count, AppContext.config.tempFolder)
        // Pieces need the exact size; ask the server for the formats yt-dlp gave only an estimate for.
        val unknown = choice.formats.filter { it.kind == YtFormat.Kind.FILE && it.fileSize == null && it.url != null }
        if (unknown.isEmpty()) return start(YtDlpTasks.build(resolved, choice, save))
        btnDownload.isEnabled = false
        lblFound.text = text("VD_PROBING")
        val gen = generation
        Thread({
            val sizes = unknown.mapNotNull { f -> probeSize(f)?.let { f.id to it } }.toMap()
            SwingUtilities.invokeLater {
                if (gen != generation || !isDisplayable) return@invokeLater
                start(YtDlpTasks.build(resolved, choice, save, sizes))
            }
        }, "yt-dlp-probe").apply { isDaemon = true }.start()
    }

    private fun start(task: YtDlpTask) {
        val downloader = AppContext.downloader
        when (task) {
            is YtDlpTask.Http -> downloader.startHttpDownload(task.task)
            is YtDlpTask.Hls -> downloader.startHlsDownload(task.task)
            is YtDlpTask.Dash -> downloader.startDashDownload(task.task)
        }
        dispose()
    }

    /** The total size from a one-byte range request (`Content-Range: bytes 0-0/<total>`). */
    private fun probeSize(f: YtFormat): Long? {
        val c = AppContext.config
        val client = HttpClientImpl(1, c.toProxy(), readTimeoutSeconds = 20, proxyUser = c.proxyUser, proxyPassword = c.proxyPass)
        return try {
            client.getResponse(f.url!!, YtDlpTasks.headerMap(f.headers), f.cookieHeader, Range(0, 0)).getOrNull()?.use { r ->
                r.getHeader("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                    ?: r.contentLength?.takeIf { r.statusCode == 200 && it > 0 }
            }
        } catch (e: Exception) {
            Logger.info("ytdlp", "Size probe failed for format ${f.id}: $e")
            null
        } finally {
            client.close()
        }
    }

    // ---- tools ----------------------------------------------------------------------------

    /** Looks the tools up and reads their versions, off the EDT; leaves the tools page when all are there. */
    private fun refreshTools(advance: Boolean = autoAdvance) {
        lookingUp = true
        toolRows.values.forEach { it.checking(true) }
        updateButtons()
        val lookups = HashMap<ExternalTool, FoundTool?>()
        val read = HashMap<ExternalTool, String?>()
        // Each row shown as soon as its own tool answers.
        ExternalTool.entries.forEach { tool ->
            Thread({
                val (f, v) = ExternalTools.check(tool)
                val installed = ToolInstaller.installed()
                SwingUtilities.invokeLater {
                    if (!isDisplayable) return@invokeLater
                    lookups[tool] = f
                    read[tool] = v
                    installedTools = installed
                    found = (found.orEmpty() - tool) + (tool to f)
                    versions = versions + (tool to v)
                    showTool(tool)
                    toolRows.getValue(tool).checking(false)
                    if (lookups.size < ExternalTool.entries.size) return@invokeLater
                    found = lookups.toMap()
                    lookingUp = false
                    lblVersions.text = ExternalTool.entries.joinToString("  ·  ") { "${it.id} ${read[it] ?: "?"}" }
                    updateButtons()
                    if (page == PAGE_TOOLS && advance && toolsReady()) showPage(PAGE_URL)
                }
            }, "yt-dlp-tools").apply { isDaemon = true }.start()
        }
    }

    private fun showTool(tool: ExternalTool) {
        val r = toolRows.getValue(tool)
        val f = found?.get(tool)
        val custom = ExternalTools.customPath(tool)
        // A custom path that does not work stays in the field, so the user sees what is wrong.
        r.path.text = f?.path ?: custom
        r.path.toolTipText = f?.path
        if (f == null) {
            r.status.text = text("VD_NOT_FOUND")
            r.status.foreground = MISSING
        } else {
            val origin = when (f.origin) {
                ToolOrigin.MANAGED -> text("VD_ORIGIN_MANAGED")
                ToolOrigin.CUSTOM -> text("VD_ORIGIN_CUSTOM")
                ToolOrigin.SYSTEM -> text("VD_ORIGIN_SYSTEM")
            }
            r.status.text = "${versions[tool] ?: "?"} · $origin"
            r.status.foreground = muted()
        }
        r.status.toolTipText = r.status.text
    }

    /** The user typed a path: a new one becomes the custom path, an empty one goes back to the lookup. */
    private fun pathEdited(tool: ExternalTool) {
        if (installing || lookingUp || found == null) return
        val typed = toolRows.getValue(tool).path.text.trim()
        val current = found?.get(tool)?.path ?: ExternalTools.customPath(tool)
        if (typed == current) return
        ExternalTools.setCustomPath(tool, if (typed == ExternalTools.managedFile(tool).absolutePath) "" else typed)
        refreshTools(advance = false)
    }

    private fun browse(tool: ExternalTool) {
        val current = toolRows.getValue(tool).path.text.trim().takeIf { it.isNotEmpty() }?.let { File(it).parentFile }
        val file = chooseFile(this, directoriesOnly = false, currentDir = current) ?: return
        ExternalTools.setCustomPath(tool, file.absolutePath)
        refreshTools(advance = false)
    }

    private fun canUpdate(tool: ExternalTool): Boolean {
        val f = found?.get(tool) ?: return false
        val l = latest[tool] ?: return false
        return f.origin == ToolOrigin.MANAGED && ToolInstaller.isNewer(installedTools[tool], l)
    }

    private fun checkUpdates() {
        lookingUp = true
        lblUpdates.text = text("VD_CHECKING")
        updateButtons()
        Thread({
            val installed = ToolInstaller.installed()
            val results = OkToolHttp().use { http ->
                ExternalTool.entries.associateWith { tool -> runCatching { ToolInstaller.latest(tool, http) } }
            }
            SwingUtilities.invokeLater {
                lookingUp = false
                if (!isDisplayable) return@invokeLater
                installedTools = installed
                latest = results.mapNotNull { (k, v) -> v.getOrNull()?.let { k to it } }.toMap()
                val failure = results.values.firstNotNullOfOrNull { it.exceptionOrNull() }
                val newer = ExternalTool.entries.filter { canUpdate(it) }
                lblUpdates.text = when {
                    failure != null -> text("VD_CHECK_FAILED").format(failure.message ?: failure.javaClass.simpleName)
                    newer.isNotEmpty() -> text("VD_UPDATES_FOUND").format(newer.joinToString(", ") { "${it.id} ${latest[it]?.label}" })
                    else -> text("VD_UP_TO_DATE")
                }
                updateButtons()
            }
        }, "yt-dlp-check").apply { isDaemon = true }.start()
    }

    /**
     * Downloads [tools] into the tools folder one after another, then looks them up again. An installed
     * tool replaces the user's own copy for that tool, as installing is asking for XDM's.
     */
    private fun install(tools: List<ExternalTool>, thenResolve: Boolean = false) {
        if (tools.isEmpty() || installing) return
        installing = true
        updateButtons()
        val cancelled = AtomicBoolean()
        val status = { tool: ExternalTool, s: String ->
            SwingUtilities.invokeLater {
                toolRows.getValue(tool).status.apply { text = s; foreground = muted(); toolTipText = s }
                lblUpdates.text = "${tool.id}: $s"
                if (thenResolve) lblLoading.text = "${tool.id}: $s"
            }
        }
        lblInstall.text = " "
        Thread({
            var failed: String? = null
            OkToolHttp().use { http ->
                for (tool in tools) {
                    if (closed.get()) break
                    try {
                        status(tool, text("VD_CHECKING"))
                        val release = latest[tool] ?: ToolInstaller.latest(tool, http)
                        var lastShown = 0L
                        ToolInstaller.install(release, tool, http, cancelled, { done, total ->
                            val now = System.currentTimeMillis()
                            if (now - lastShown > 250) {
                                lastShown = now
                                val amount = FormatHelper.formatSize(done.toDouble()) +
                                    (total?.let { " / " + FormatHelper.formatSize(it.toDouble()) } ?: "")
                                status(tool, text("VD_DOWNLOADING_TOOL").format(amount))
                            }
                            if (closed.get()) cancelled.set(true)
                        })
                        ExternalTools.forget(tool)
                        SwingUtilities.invokeLater { ExternalTools.setCustomPath(tool, "") }
                    } catch (e: Exception) {
                        Logger.error("ytdlp", "Installing ${tool.id} failed", e)
                        failed = if (e is ToolInstallException && e.message == "busy") text("VD_TOOLS_BUSY")
                        else text("VD_INSTALL_FAILED").format(tool.id, e.message ?: e.javaClass.simpleName)
                        break
                    }
                }
            }
            SwingUtilities.invokeLater {
                installing = false
                if (!isDisplayable) return@invokeLater
                val msg = failed ?: " "
                lblInstall.text = msg
                lblUpdates.text = msg
                if (failed != null && thenResolve) showProblem(failed!!)
                // A finished install on the tools page moves on, as the first lookup would have.
                refreshTools(advance = failed == null)
                if (latest.isNotEmpty()) checkUpdates()
                if (thenResolve && failed == null) waitForToolsThenResolve()
            }
        }, "yt-dlp-install").apply { isDaemon = true }.start()
    }

    /** After an update from page 3: run yt-dlp again once the lookup that follows the install is done. */
    private fun waitForToolsThenResolve() {
        if (!isDisplayable) return
        if (lookingUp || installing) {
            javax.swing.Timer(200) { waitForToolsThenResolve() }.apply { isRepeats = false }.start()
            return
        }
        resolve()
    }

    override fun dispose() {
        closed.set(true)
        cancel.set(true)
        ytProcess.get()?.destroy()
        picker.dispose()
        AppContext.config.save()
        super.dispose()
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun clipboardUrl(): String? = runCatching {
        (Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String)
            ?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }.getOrNull()

    private fun muted(): Color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

    private fun wrappingText(s: String) = JTextArea(s).apply {
        isEditable = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = null
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A quality row: film or music icon, the label and the size (`~` when it is yt-dlp's estimate). */
    private class ChoiceRenderer : DefaultListCellRenderer() {
        private val videoIcon = createIcon(RemixIcon.FILM_LINE, 24, Color.GRAY)
        private val audioIcon = createIcon(RemixIcon.MUSIC_2_LINE, 24, Color.GRAY)

        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val choice = value as? VideoChoice
            if (choice != null) {
                val size = choice.size?.let { (if (choice.sizeExact) "" else "~") + FormatHelper.formatSize(it.toDouble()) }
                text = listOfNotNull(choice.label, size).joinToString(" · ")
                icon = if (choice.audioOnly) audioIcon else videoIcon
            }
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
        private const val PAGE_TOOLS = "tools"
        private const val PAGE_URL = "url"
        private const val PAGE_FORMATS = "formats"
        private const val FORMATS_LOADING = "loading"
        private const val FORMATS_RESULTS = "results"
        private const val ROW_CHECKING = "checking"
        private const val ROW_READY = "ready"
        private val MISSING = Color(0xD9, 0x53, 0x4F)
        private const val DIALOG_WIDTH = 720
        private const val DIALOG_HEIGHT = 560
        private const val MIN_WIDTH = 560
        private const val MIN_HEIGHT = 420
    }
}
