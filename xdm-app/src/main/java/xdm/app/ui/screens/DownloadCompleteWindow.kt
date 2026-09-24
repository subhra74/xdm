package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import javax.swing.SwingConstants
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
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = Component.CENTER_ALIGNMENT
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
    private val lblSkipNotification = JLabel(text("MSG_DONT_SHOW_AGAIN")).apply {
        foreground = mutedFg
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                AppContext.config.showDownloadCompleteWindow = false
                dispose()
            }

            override fun mouseEntered(e: MouseEvent) {
                foreground = accent
            }

            override fun mouseExited(e: MouseEvent) {
                foreground = mutedFg
            }
        })
    }

    fun setDetails(file: String, folder: String, fileSize: Long) {
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

    private fun caption(key: String) = JLabel(text(key)).apply {
        foreground = mutedFg
        font = font.deriveFont(font.size2D - 1f)
    }

    private fun infoCard(): JComponent {
        val card = JPanel(GridBagLayout()).apply {
            background = cardBg
            border = BorderFactory.createEmptyBorder(12, 14, 12, 8)
            putClientProperty("FlatLaf.style", "arc: 16")
        }

        fun row(gridY: Int, captionKey: String, field: JTextField, top: Int) {
            card.add(caption(captionKey), GridBagConstraints().apply {
                gridx = 0; gridy = gridY; anchor = GridBagConstraints.WEST
                insets = Insets(top, 0, 0, 0)
            })
            card.add(field, GridBagConstraints().apply {
                gridx = 0; gridy = gridY + 1
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = Insets(1, 0, 0, 8)
            })
            card.add(copyButton(field), GridBagConstraints().apply {
                gridx = 1; gridy = gridY + 1; gridheight = 1
                anchor = GridBagConstraints.EAST
            })
        }

        row(0, "ND_FILE", txtFileName, 0)
        row(2, "CD_LOC", txtFolderPath, 10)
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

        val header = Box.createVerticalBox().apply {
            alignmentX = Component.CENTER_ALIGNMENT
            add(JLabel(createIcon(RemixIcon.CHECKBOX_CIRCLE_FILL, 44, Color(0x2ECC71))).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(10))
            add(JLabel(text("CD_TITLE")).apply {
                font = font.deriveFont(Font.BOLD, 16f)
                foreground = UIManager.getColor("Label.foreground")
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createVerticalStrut(4))
            add(lblFileSize)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(24, 24, 16, 24)
            add(header)
            add(Box.createVerticalStrut(18))
            add(infoCard().apply { alignmentX = Component.CENTER_ALIGNMENT })
        }

        val footer = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 24, 18, 24)
            add(lblSkipNotification, BorderLayout.WEST)
            add(Box.createHorizontalBox().apply {
                add(btnOpenFolder)
                add(Box.createHorizontalStrut(8))
                add(btnOpenFile)
            }, BorderLayout.EAST)
        }

        contentPane.layout = BorderLayout()
        contentPane.add(content, BorderLayout.CENTER)
        contentPane.add(footer, BorderLayout.SOUTH)

        rootPane.defaultButton = btnOpenFile

        pack()
        isResizable = false
        minimumSize = Dimension(440, height)
        size = Dimension(440, height)
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
