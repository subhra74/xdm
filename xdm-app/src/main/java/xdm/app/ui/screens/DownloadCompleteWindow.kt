package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.components.ChecksumDialog
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
import xdm.app.utils.px
import xdm.app.utils.scaledInsets
import xdm.app.utils.windowIconImages
import xdm.core.checksum.ChecksumSource
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.Timer
import javax.swing.TransferHandler
import javax.swing.UIManager

class DownloadCompleteWindow : JFrame() {

    private val accent: Color = UIManager.getColor("Component.focusColor")
        ?: UIManager.getColor("ProgressBar.foreground")
        ?: Color(0x3B82F6)
    private val mutedFg: Color = UIManager.getColor("Label.disabledForeground")
        ?: Color(0x9AA0A6)
    private val cardBg: Color = UIManager.getColor("Table.background")
        ?: UIManager.getColor("TextField.background")
        ?: Color(0x2B2B2B)

    private val txtFolderPath = borderlessField()
    private val txtFileName = borderlessField()
    private val btnCopyFileName = copyButton(txtFileName)
    private val lblFileSize = JLabel("---").apply {
        foreground = mutedFg
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private val btnOpenFile = JButton(text("CTX_OPEN_FILE")).apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener {
            try {
                openFileExternal(txtFileName.text, txtFolderPath.text)
                dispose()
            } catch (e: Exception) {
                Logger.error(e)
            }
        }
    }
    private val btnOpenFolder = JButton(text("CTX_OPEN_FOLDER")).apply {
        addActionListener {
            try {
                openFolderExternal(txtFileName.text, txtFolderPath.text)
                dispose()
            } catch (e: Exception) {
                Logger.error(e)
            }
        }
    }
    private val btnSkipNotification = footerIconButton(RemixIcon.NOTIFICATION_OFF_FILL, text("MSG_DONT_SHOW_AGAIN")) {
        AppContext.config.showDownloadCompleteWindow = false
        dispose()
    }

    /** Request of a single-URL HTTP download; null (HLS/DASH, batch, torrent) hides [btnVerify]. */
    private var checksumSource: ChecksumSource? = null

    /** Opens verification in its own non-modal window and closes this one, as the open buttons do. */
    private val btnVerify = footerIconButton(RemixIcon.FINGERPRINT_FILL, text("CD_VERIFY_CHECKSUM")) {
        val source = checksumSource ?: return@footerIconButton
        // No owner: disposing this dialog must not take the verify window with it.
        ChecksumDialog(null, File(txtFolderPath.text, txtFileName.text), source).isVisible = true
        dispose()
    }.apply { isVisible = false }

    /**
     * Drag handle: dropping it on a folder in Explorer, Finder or a file manager moves the file there. The drop
     * target does the move; on another disk it may only copy, and the original is deliberately never deleted here.
     */
    private var downloadedFile = File("")

    private val btnDrag = footerIconButton(RemixIcon.DRAG_DROP_FILL, text("CD_DRAG_TO_MOVE")) {}.apply {
        transferHandler = FileDragHandler()
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val dragStarter = object : MouseAdapter() {
            private var started = false

            override fun mousePressed(e: MouseEvent) {
                started = false
            }

            override fun mouseDragged(e: MouseEvent) {
                // Swing still delivers mouse events to a disabled button.
                if (started || !isEnabled) return
                started = true
                transferHandler.exportAsDrag(e.component as JComponent, e, TransferHandler.MOVE)
            }
        }
        addMouseListener(dragStarter)
        addMouseMotionListener(dragStarter)
    }

    private inner class FileDragHandler : TransferHandler() {
        override fun getSourceActions(c: JComponent) = MOVE

        override fun createTransferable(c: JComponent): Transferable? =
            if (downloadedFile.exists()) FileTransferable(downloadedFile) else null

        /**
         * The file is gone only if the target really moved it: a copy to another disk leaves it, and nothing
         * changes. Checked again a moment later in case the target (e.g. Finder) moves after the drop returns.
         */
        override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
            if (data == null || action != MOVE) return
            if (!downloadedFile.exists()) {
                showMoved()
            } else {
                Timer(1000) { if (!downloadedFile.exists()) showMoved() }.apply { isRepeats = false }.start()
            }
        }
    }

    /** What the window shows no longer points at the file, so stop offering what needs it. */
    private fun showMoved() {
        txtFileName.text = text("CD_FILE_MOVED")
        txtFileName.toolTipText = null
        txtFileName.caretPosition = 0
        btnCopyFileName.isEnabled = false
        btnVerify.isEnabled = false
        btnDrag.isEnabled = false
    }

    /** A single file as `javaFileListFlavor`; AWT offers it to native targets as a file drop (`text/uri-list` on Linux). */
    private class FileTransferable(private val file: File) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return listOf(file)
        }
    }

    fun setDetails(file: String, folder: String, fileSize: Long, checksumSource: ChecksumSource?) {
        this.checksumSource = checksumSource
        btnVerify.isVisible = checksumSource != null
        downloadedFile = File(folder, file)
        btnDrag.isVisible = downloadedFile.exists()
        fitToContent()
        txtFolderPath.text = folder
        txtFileName.text = file
        txtFolderPath.caretPosition = 0
        txtFileName.caretPosition = 0
        txtFileName.toolTipText = file
        txtFolderPath.toolTipText = folder
        lblFileSize.text = formatSize(fileSize.toDouble())
    }

    private fun borderlessField() = JTextField().apply {
        isEditable = false
        border = BorderFactory.createEmptyBorder()
        isOpaque = false
        // columns = 1 keeps the preferred/minimum width tiny and independent of the
        // text length, so a long file name or path is truncated (with a tooltip) rather
        // than stretching the dialog. fill=HORIZONTAL lets it grow to the card width only.
        columns = 1
        minimumSize = Dimension(0, preferredSize.height)
    }

    private fun copyButton(source: JTextField) = JButton(
        createIcon(RemixIcon.FILE_COPY_LINE, 16, mutedFg)
    ).apply {
        putClientProperty("JButton.buttonType", "toolBarButton")
        putClientProperty("JButton.squareSize", true)
        toolTipText = text("CTX_COPY")
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(source.text), null)
            val original = icon
            icon = createIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, 16, Color(0x2ECC71))
            Timer(1100) { icon = original }.apply { isRepeats = false }.start()
        }
    }

    /**
     * Makes the packed size (at least the usual 400 wide) the floor the user can resize from. Run
     * again when the footer changes: at a large UI scale or with a long translation the footer can
     * be wider than 400, and the fingerprint button is only shown after the first pack.
     */
    private fun fitToContent() {
        val preferred = preferredSize
        val min = Dimension(maxOf(400.px, preferred.width), preferred.height)
        minimumSize = min
        size = Dimension(maxOf(width, min.width), maxOf(height, min.height))
    }

    /** Borderless icon-only button for the left of the footer. */
    private fun footerIconButton(icon: RemixIcon, tooltip: String, action: () -> Unit) =
        JButton(createIcon(icon, 18, mutedFg)).apply {
            putClientProperty("JButton.buttonType", "toolBarButton")
            putClientProperty("JButton.squareSize", true)
            rolloverIcon = createIcon(icon, 18, accent)
            toolTipText = tooltip
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { action() }
        }

    private fun caption(key: String) = JLabel(text(key)).apply {
        foreground = mutedFg
        font = font.deriveFont(font.size2D - 1f)
    }

    private fun infoCard(): JComponent {
        val card = JPanel(GridBagLayout()).apply {
            background = cardBg
            border = ScaledEmptyBorder(8, 12, 8, 6)
            putClientProperty("FlatLaf.style", "arc: 12")
        }

        fun row(gridY: Int, captionKey: String, field: JTextField, copy: JButton, top: Int) {
            card.add(caption(captionKey), GridBagConstraints().apply {
                gridx = 0; gridy = gridY; anchor = GridBagConstraints.WEST
                insets = Insets(top.px, 0, 0, 0)
            })
            card.add(field, GridBagConstraints().apply {
                gridx = 0; gridy = gridY + 1
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = scaledInsets(1, 0, 0, 8)
            })
            card.add(copy, GridBagConstraints().apply {
                gridx = 1; gridy = gridY + 1; gridheight = 1
                anchor = GridBagConstraints.EAST
            })
        }

        row(0, "ND_FILE", txtFileName, btnCopyFileName, 0)
        row(2, "CD_LOC", txtFolderPath, copyButton(txtFolderPath), 6)
        return card
    }

    init {
        isAlwaysOnTop = true
        // Show the notification without pulling focus away from whatever the user is
        // doing: the dialog appears on top but the active window keeps keyboard focus
        // until the user clicks this one.
        isAutoRequestFocus = false
        title = text("CD_TITLE")
        // A top-level frame (so it can be minimized) with its own taskbar button: without this it shows Java's icon.
        iconImages = windowIconImages
        defaultCloseOperation = DISPOSE_ON_CLOSE

        // Compact header: icon on the left, title and size stacked beside it.
        val header = Box.createHorizontalBox().apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(createIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, 32, Color(0x2ECC71))))
            add(Box.createHorizontalStrut(10.px))
            add(Box.createVerticalBox().apply {
                add(JLabel(text("CD_TITLE")).apply {
                    font = font.deriveFont(Font.BOLD, 14f.px)
                    foreground = UIManager.getColor("Label.foreground")
                    alignmentX = Component.LEFT_ALIGNMENT
                })
                add(Box.createVerticalStrut(2.px))
                add(lblFileSize)
            })
            add(Box.createHorizontalGlue())
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = ScaledEmptyBorder(14, 16, 10, 16)
            // Fixed heights plus glue: when the window is made taller the rows stay together at the top.
            add(header.apply { maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height) })
            add(Box.createVerticalStrut(10.px))
            add(infoCard().apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            add(Box.createVerticalGlue())
        }

        val footer = JPanel(BorderLayout()).apply {
            border = ScaledEmptyBorder(0, 16, 14, 16)
            add(Box.createHorizontalBox().apply {
                add(btnDrag)
                add(btnVerify)
                add(btnSkipNotification)
            }, BorderLayout.WEST)
            add(Box.createHorizontalBox().apply {
                add(btnOpenFolder)
                add(Box.createHorizontalStrut(8.px))
                add(btnOpenFile)
            }, BorderLayout.EAST)
        }

        contentPane.layout = BorderLayout()
        contentPane.add(content, BorderLayout.CENTER)
        contentPane.add(footer, BorderLayout.SOUTH)

        rootPane.defaultButton = btnOpenFile

        pack()
        isResizable = true
        fitToContent()
        setLocationRelativeTo(null)

        addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent) {
                btnOpenFile.requestFocusInWindow()
            }

            override fun windowClosed(e: WindowEvent) {
                System.gc()
            }
        })
    }
}
