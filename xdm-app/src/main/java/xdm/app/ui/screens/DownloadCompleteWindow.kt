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
import java.awt.datatransfer.StringSelection
import java.io.File
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.Timer
import javax.swing.UIManager

class DownloadCompleteWindow : JDialog() {

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

    fun setDetails(file: String, folder: String, fileSize: Long, checksumSource: ChecksumSource?) {
        this.checksumSource = checksumSource
        btnVerify.isVisible = checksumSource != null
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

        fun row(gridY: Int, captionKey: String, field: JTextField, top: Int) {
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
            card.add(copyButton(field), GridBagConstraints().apply {
                gridx = 1; gridy = gridY + 1; gridheight = 1
                anchor = GridBagConstraints.EAST
            })
        }

        row(0, "ND_FILE", txtFileName, 0)
        row(2, "CD_LOC", txtFolderPath, 6)
        return card
    }

    init {
        isAlwaysOnTop = true
        // Show the notification without pulling focus away from whatever the user is
        // doing: the dialog appears on top but the active window keeps keyboard focus
        // until the user clicks this one.
        isAutoRequestFocus = false
        title = text("CD_TITLE")
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
