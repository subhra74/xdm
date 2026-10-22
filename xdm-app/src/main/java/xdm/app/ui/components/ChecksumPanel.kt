package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.windowIconImages
import xdm.core.checksum.ChecksumAlgorithm
import xdm.core.checksum.ChecksumParser
import xdm.core.checksum.ChecksumResult
import xdm.core.checksum.ChecksumSource
import xdm.core.checksum.ChecksumVerifier
import xdm.core.checksum.NotFoundReason
import xdm.core.checksum.SidecarUrls
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Verifies a finished download against a published checksum: a sidecar next to the download
 * (`file.sha256`), a folder manifest (`SHA256SUMS`), any checksum URL the user enters, or a hash
 * pasted directly. The parsing and hashing live in [ChecksumVerifier]; this is only the UI.
 *
 * Two pages under one header: the form (algorithm and checksum URL or hash) and, once Verify is
 * pressed, the run (progress, then the result). Back returns to the form as it was left.
 *
 * Hosted by [ChecksumWindow], opened from the download complete dialog and from properties.
 * [source] is the request the file came from, null when there is none to derive a sidecar from;
 * the field then starts empty for the user to fill.
 */
class ChecksumPanel(
    private val file: File,
    private val source: ChecksumSource?,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    private val accent: Color = UIManager.getColor("Component.focusColor")
        ?: UIManager.getColor("ProgressBar.foreground")
        ?: Color(0x3B82F6)
    private val mutedFg: Color = UIManager.getColor("Label.disabledForeground") ?: Color(0x9AA0A6)
    private val cardBg: Color = UIManager.getColor("Table.background")
        ?: UIManager.getColor("TextField.background")
        ?: Color(0x2B2B2B)

    /** Names a checksum manifest may list the file under: as saved, and as named in the URL. */
    private val fileNames = listOfNotNull(file.name, source?.url?.let { SidecarUrls.remoteFileName(it) }).distinct()

    /** Null is "Auto". */
    private var algorithm: ChecksumAlgorithm? = null

    /** The URL last filled in for the user; while the field still holds it, picking an algorithm replaces it. */
    private var suggested = ""

    /** Set while a verification runs; its flag is raised to stop it. */
    private var worker: AtomicBoolean? = null

    // Form page.
    private val txtInput = JTextField().apply {
        columns = 1 // width from the panel, not from a long URL (as in DownloadCompleteWindow)
        putClientProperty("FlatLaf.style", "arc: 10")
        getAccessibleContext().accessibleName = text("CS_INPUT")
        putClientProperty("JTextField.placeholderText", "https://example.com/file.iso.sha256")
        putClientProperty("JTextField.showClearButton", true)
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = showHint(null)
            override fun removeUpdate(e: DocumentEvent) = showHint(null)
            override fun changedUpdate(e: DocumentEvent) = showHint(null)
        })
    }

    /** Under the field: what it accepts, or why Verify refused the input. */
    private val lblHint = JLabel().apply { iconTextGap = 6 } // FlatLaf scales iconTextGap itself

    // Run page.
    private val lblOutcome = JLabel().apply {
        iconTextGap = 8
        font = font.deriveFont(Font.BOLD, 14f.px)
    }

    /** Where the hash came from (or the URL being fetched), clipped with the full text as tooltip. */
    private val lblFrom = clippedLabel()
    private val progress = JProgressBar(0, 1000)
    private val txtExpected = readOnlyField()
    private val txtActual = readOnlyField()
    private val hashRows = JPanel(GridBagLayout()).apply { isOpaque = false }

    // Footer: the buttons for each state share two slots; only the current state's are visible.
    private val btnClose = JButton(text("LBL_CLOSE")).apply { addActionListener { onClose() } }
    private val btnBack = JButton(text("CS_BACK"), createIcon(RemixIcon.ARROW_LEFT_LINE, 16)).apply {
        addActionListener { showForm() }
    }
    private val btnVerify = JButton(text("CS_VERIFY")).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { start() }
    }
    private val btnCancel = JButton(text("ND_CANCEL")).apply {
        addActionListener {
            cancel()
            showForm()
        }
    }
    private val btnDone = JButton(text("LBL_CLOSE")).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { onClose() }
    }

    private val pages = CardLayout()
    private val pageHost = JPanel(pages).apply { isOpaque = false }

    init {
        isOpaque = false
        pageHost.add(formPage(), PAGE_FORM)
        pageHost.add(runPage(), PAGE_RUN)
        val content = JPanel(BorderLayout(0, 12.px)).apply {
            isOpaque = false
            border = ScaledEmptyBorder(14, 16, 10, 16)
            add(header(), BorderLayout.NORTH)
            add(pageHost, BorderLayout.CENTER)
        }
        add(content, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)
        showHint(null)
        suggest()
        showForm()
    }

    /** The default button follows the page, so it can only be set once the panel is in a window. */
    override fun addNotify() {
        super.addNotify()
        updateDefaultButton()
    }

    /** Stops a running verification; call when the window goes away. */
    fun cancel() {
        worker?.set(true)
        worker = null
    }

    /** Icon beside the title and the file name, like the download complete dialog's header. */
    private fun header(): JComponent = Box.createHorizontalBox().apply {
        add(JLabel(createIcon(RemixIcon.FINGERPRINT_FILL, 32, accent)))
        add(Box.createHorizontalStrut(10.px))
        add(Box.createVerticalBox().apply {
            add(JLabel(text("CS_TITLE")).apply {
                font = font.deriveFont(Font.BOLD, 14f.px)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(2.px))
            add(clippedLabel().apply {
                text = file.name
                foreground = mutedFg
                toolTipText = file.absolutePath
            })
        })
    }

    private fun formPage(): JComponent = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(caption("CS_ALGORITHM"))
        add(Box.createVerticalStrut(3.px))
        add(algorithmPicker())
        add(Box.createVerticalStrut(10.px))
        add(caption("CS_INPUT"))
        add(Box.createVerticalStrut(3.px))
        add(txtInput.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })
        add(Box.createVerticalStrut(6.px))
        add(lblHint.apply { alignmentX = Component.LEFT_ALIGNMENT })
        add(Box.createVerticalGlue())
    }

    /** Outcome line, its source, then the progress bar while working or the hashes once done. */
    private fun runPage(): JComponent {
        fun hashRow(y: Int, key: String, field: JTextField) {
            hashRows.add(JLabel(text(key)).apply {
                foreground = mutedFg
                font = font.deriveFont(font.size2D - 1f)
            }, GridBagConstraints().apply {
                gridx = 0; gridy = y; anchor = GridBagConstraints.WEST
                insets = scaledInsets(1, 0, 1, 8)
            })
            hashRows.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = y; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            })
        }
        hashRow(0, "CS_EXPECTED", txtExpected)
        hashRow(1, "CS_ACTUAL", txtActual)

        val card = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = cardBg
            border = ScaledEmptyBorder(10, 12, 10, 12)
            putClientProperty("FlatLaf.style", "arc: 12")
            alignmentX = Component.LEFT_ALIGNMENT
            add(lblOutcome.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(2.px))
            add(lblFrom)
            add(Box.createVerticalStrut(8.px))
            add(progress.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            add(hashRows.apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
        return JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(card)
            add(Box.createVerticalGlue())
        }
    }

    /** Auto plus one toggle per algorithm, one row of equal segments. */
    private fun algorithmPicker(): JComponent {
        val group = ButtonGroup()
        val row = JPanel(GridLayout(1, 0, 4.px, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val choices = listOf<Pair<String, ChecksumAlgorithm?>>(text("CS_AUTO") to null) +
            ChecksumAlgorithm.entries.map { it.label to it }
        choices.forEach { (label, value) ->
            val button = JToggleButton(label).apply {
                putClientProperty("JButton.buttonType", "toolBarButton")
                putClientProperty("FlatLaf.style", "arc: 8; margin: 3,4,3,4")
                isSelected = value == algorithm
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    algorithm = value
                    suggest()
                }
            }
            group.add(button)
            row.add(button)
        }
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    private fun footer(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = ScaledEmptyBorder(0, 16, 14, 16)
        add(Box.createHorizontalBox().apply {
            add(btnClose)
            add(btnBack)
        }, BorderLayout.WEST)
        add(Box.createHorizontalBox().apply {
            add(btnVerify)
            add(btnCancel)
            add(btnDone)
        }, BorderLayout.EAST)
    }

    private fun caption(key: String) = JLabel(text(key)).apply {
        foreground = mutedFg
        font = font.deriveFont(font.size2D - 1f)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /** A label that is clipped, never widening the window, however long its text. */
    private fun clippedLabel() = object : JLabel() {
        override fun getPreferredSize(): Dimension = Dimension(1, super.getPreferredSize().height)
        override fun getMinimumSize(): Dimension = Dimension(0, super.getPreferredSize().height)
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, super.getPreferredSize().height)
    }.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private fun readOnlyField() = JTextField().apply {
        isEditable = false
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        columns = 1
        // FlatLaf's monospaced font is already scaled; only match it to the label size.
        font = (UIManager.getFont("monospaced.font") ?: Font(Font.MONOSPACED, Font.PLAIN, font.size))
            .deriveFont(font.size2D - 1f)
        minimumSize = Dimension(0, preferredSize.height)
    }

    /** Fills the field with the sidecar for the chosen algorithm, unless the user typed their own. */
    private fun suggest() {
        val url = source?.url ?: return
        val current = txtInput.text.trim()
        if (current.isNotEmpty() && current != suggested) return
        // Auto shows the most common sidecar; Verify then probes the others too.
        suggested = SidecarUrls.sidecar(url, algorithm ?: ChecksumAlgorithm.SHA256) ?: return
        txtInput.text = suggested
        txtInput.caretPosition = 0
    }

    /** [error] null shows what the field accepts; otherwise why Verify refused it. */
    private fun showHint(error: String?) {
        lblHint.text = error ?: text("CS_IDLE")
        lblHint.icon = error?.let { createIcon(RemixIcon.ERROR_WARNING_FILL, 16, WARNING) }
        lblHint.foreground = if (error != null) WARNING else mutedFg
    }

    private fun showForm() {
        pages.show(pageHost, PAGE_FORM)
        setButtons(btnClose, btnVerify)
        txtInput.requestFocusInWindow()
    }

    /** Shows only [visible] of the footer buttons and makes the primary one the default. */
    private fun setButtons(vararg visible: JButton) {
        listOf(btnClose, btnBack, btnVerify, btnCancel, btnDone).forEach { it.isVisible = it in visible }
        updateDefaultButton()
    }

    private fun updateDefaultButton() {
        rootPane?.defaultButton = listOf(btnVerify, btnDone).firstOrNull { it.isVisible }
    }

    private fun showWorking(message: String, from: String?) {
        setOutcome(message, null, UIManager.getColor("Label.foreground"), from)
        progress.isVisible = true
        hashRows.isVisible = false
    }

    private fun setOutcome(message: String, icon: Icon?, color: Color, from: String?) {
        lblOutcome.text = message
        lblOutcome.icon = icon
        lblOutcome.foreground = color
        lblOutcome.toolTipText = message
        lblFrom.text = from ?: ""
        lblFrom.toolTipText = from
        lblFrom.foreground = mutedFg
    }

    private fun start() {
        val input = txtInput.text.trim()
        val chosen = algorithm
        val error = when {
            input.isEmpty() -> text("CS_ERR_INPUT")
            ChecksumParser.parseRawHash(input) == null && !isHttpUrl(input) -> text("CS_ERR_INPUT")
            !file.isFile -> text("CS_ERR_FILE")
            else -> null
        }
        if (error != null) {
            showHint(error)
            txtInput.requestFocusInWindow()
            return
        }
        // Auto probes every conventional sidecar only while the field still holds the suggestion.
        val probe = chosen == null && input == suggested
        val stop = AtomicBoolean(false)
        worker = stop
        pages.show(pageHost, PAGE_RUN)
        setButtons(btnCancel)
        progress.isIndeterminate = true
        showWorking(text("CS_FETCHING"), null)

        Thread({
            val client = newHttpClient()
            val result = try {
                val verifier = ChecksumVerifier(client) { stop.get() }
                Result.success(verifier.verify(file, input, chosen, source, fileNames, probe, ProgressRelay(stop)))
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                Logger.error(e)
                Result.failure(e)
            } finally {
                client.close()
            }
            SwingUtilities.invokeLater { finished(stop, result) }
        }, "checksum-verify").apply { isDaemon = true }.start()
    }

    /** Forwards progress to the EDT while [stop] is not raised. */
    private inner class ProgressRelay(private val stop: AtomicBoolean) : ChecksumVerifier.Progress {
        override fun fetching(url: String) = onEdt {
            progress.isIndeterminate = true
            showWorking(text("CS_FETCHING"), url)
        }

        override fun hashing(algorithm: ChecksumAlgorithm, done: Long, total: Long) = onEdt {
            progress.isIndeterminate = total <= 0
            val permille = if (total > 0) (done * 1000 / total).toInt() else 0
            progress.value = permille
            showWorking(text("CS_HASHING").format(algorithm.label, permille / 10), lblFrom.toolTipText)
        }

        private fun onEdt(action: () -> Unit) = SwingUtilities.invokeLater { if (!stop.get()) action() }
    }

    private fun finished(stop: AtomicBoolean, result: Result<ChecksumResult>?) {
        // Cancelled (Cancel or Back already showed the form) or superseded by a newer run.
        if (stop.get() || worker !== stop || result == null) return
        worker = null
        progress.isIndeterminate = false
        progress.isVisible = false
        setButtons(btnBack, btnDone)
        result.fold(onSuccess = { show(it) }, onFailure = {
            showFailure(text("CS_ERR_GENERIC").format(it.message ?: it.javaClass.simpleName), null)
        })
    }

    private fun show(result: ChecksumResult) {
        when (result) {
            is ChecksumResult.Match -> {
                setOutcome(
                    text("CS_MATCH").format(result.expected.algorithm.label),
                    createIcon(RemixIcon.SHIELD_CHECK_FILL, 20, SUCCESS), SUCCESS, fromText(result.from)
                )
                setHashes(result.expected.hex, result.expected.hex)
            }

            is ChecksumResult.Mismatch -> {
                setOutcome(
                    text("CS_MISMATCH").format(result.expected.algorithm.label),
                    createIcon(RemixIcon.SHIELD_CROSS_FILL, 20, ERROR), ERROR, fromText(result.from)
                )
                setHashes(result.expected.hex, result.actualHex)
            }

            is ChecksumResult.NotFound -> showFailure(
                when (result.reason) {
                    NotFoundReason.BAD_INPUT -> text("CS_ERR_INPUT")
                    NotFoundReason.HTTP_ERROR -> text("CS_ERR_HTTP").format(result.detail)
                    NotFoundReason.NETWORK_ERROR -> text("CS_ERR_NETWORK").format(result.detail)
                    NotFoundReason.NO_HASH_FOR_FILE -> text("CS_ERR_NO_HASH")
                    NotFoundReason.NO_SIDECAR -> text("CS_ERR_NO_SIDECAR")
                },
                result.from
            )
        }
    }

    private fun fromText(url: String?): String =
        if (url != null) text("CS_FROM").format(url) else text("CS_FROM_PASTED")

    private fun setHashes(expected: String, actual: String) {
        txtExpected.text = expected
        txtActual.text = actual
        txtExpected.toolTipText = expected
        txtActual.toolTipText = actual
        txtExpected.caretPosition = 0
        txtActual.caretPosition = 0
        hashRows.isVisible = true
    }

    private fun showFailure(message: String, from: String?) {
        setOutcome(message, createIcon(RemixIcon.ERROR_WARNING_FILL, 20, WARNING), WARNING, from)
        hashRows.isVisible = false
    }

    private fun newHttpClient() = AppContext.config.let {
        HttpClientImpl(
            2, it.toProxy(), it.ignoreCertErrors, it.readTimeoutSeconds, it.proxyUser, it.proxyPass,
            auth = AppContext.httpAuth, serverAuth = true,
        )
    }

    private fun isHttpUrl(text: String) =
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

    companion object {
        private const val PAGE_FORM = "form"
        private const val PAGE_RUN = "run"
        private val SUCCESS = Color(0x2ECC71)
        private val ERROR = Color(0xE5534B)
        private val WARNING = Color(0xE0A100)

        /**
         * The request behind a finished single-URL HTTP download, or null. Checksums are not
         * offered for HLS/DASH (the output is muxed from many URLs), batches or torrents.
         * Reads the task info from disk; [AppContext] access only, so callable from any thread.
         */
        fun sourceOf(id: Long): ChecksumSource? =
            AppContext.taskInfoDB.getHttpTask(id)?.let { ChecksumSource(it.url, it.headers, it.cookie) }
    }
}

/**
 * [ChecksumPanel] in a top-level window of its own. No owner: it outlives whatever opened it (the
 * properties dialog, the download-complete window) and stays when the main window goes to the tray.
 * A frame: hashing a large file takes a while, so it can be minimized and has a taskbar button.
 * [near] is where it opens; null centres it on the screen.
 */
class ChecksumWindow(file: File, source: ChecksumSource?, near: Window?) : JFrame() {
    private val panel = ChecksumPanel(file, source, onClose = { dispose() })

    init {
        title = text("CS_TITLE")
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE
        contentPane.add(panel)
        pack()
        // Packed size is the floor: at a large UI scale or with a long translation the algorithm
        // row is wider than the usual 420 and must not be clipped. Wider is fine; the fields stretch.
        minimumSize = Dimension(maxOf(420.px, width), height)
        size = minimumSize
        isResizable = true
        setLocationRelativeTo(near)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = panel.cancel()
        })
    }
}
