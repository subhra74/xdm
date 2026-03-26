package xdm.app.ui.screens

import xdm.app.I8N.text
import xdm.app.utils.createSVGIcon
import xdm.app.utils.gbAdd
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.Logger
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.UIManager

class DownloadCompleteWindow : JDialog() {
    private val txtFolderPath = JTextField().apply { isEditable = false }
    private val txtFileName = JTextField().apply { isEditable = false }
    private val lblFileSize = JLabel("---")
    private val btnOpenFile = JButton(text("CTX_OPEN_FILE")).apply {
        addActionListener {
            try {
                openFileExternal(txtFileName.text, txtFolderPath.text)
                dispose()
            } catch (e: Exception) {
                Logger.error(e);
            }
        }
    }
    private val btnOpenFolder = JButton(text("CTX_OPEN_FOLDER")).apply {
        addActionListener {
            try {
                openFolderExternal(txtFileName.text, txtFolderPath.text)
                dispose()
            } catch (e: Exception) {
                Logger.error(e);
            }
        }
    }
    private val lblSkipNotification =
        JLabel(text("MSG_DONT_SHOW_AGAIN")).apply {
            foreground = UIManager.getColor("ProgressBar.foreground")
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

    fun setDetails(file: String, folder: String, fileSize: Long) {
        txtFolderPath.text = folder
        txtFileName.text = file
        lblFileSize.text = formatSize(fileSize.toDouble())
    }

    init {
        isAlwaysOnTop = true
        title = text("CD_TITLE")
        size = Dimension(400, 210)
        setLocationRelativeTo(null)
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val h1 = Box.createHorizontalBox().apply {
            border = BorderFactory.createEmptyBorder(10, 15, 10, 15)
            add(lblSkipNotification)
            add(Box.createHorizontalGlue())
            add(btnOpenFolder)
            add(Box.createRigidArea(Dimension(10, 5)))
            add(btnOpenFile)
            isOpaque = true
            background = UIManager.getColor("Table.background")
        }

        val v1 = Box.createVerticalBox().apply {
            add(JLabel(createSVGIcon("contract-fill.svg", 36, Color.GRAY)).apply {
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.BOTTOM
                verticalTextPosition = SwingConstants.BOTTOM
                alignmentX = Component.CENTER_ALIGNMENT
            })
            add(Box.createRigidArea(Dimension(10, 5).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }))
            add(lblFileSize.apply {
                horizontalAlignment = SwingConstants.CENTER
                alignmentX = Component.CENTER_ALIGNMENT
                verticalAlignment = SwingConstants.TOP
                verticalTextPosition = SwingConstants.TOP
            })
        }

        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 1.0, 0.0)
            rowWeights = doubleArrayOf(0.0, 0.0, 1.0, 0.0)
        }
        contentPane.layout = gridBagLayout

        gbAdd(
            JLabel(text("ND_FILE")), contentPane, padding = Insets(20, 15, 0, 0),
            alignment = GridBagConstraints.EAST
        )
        gbAdd(
            txtFileName, contentPane, padding = Insets(20, 10, 0, 15),
            alignment = GridBagConstraints.WEST,
            gridX = 1,
            horizontalFill = true
        )
        gbAdd(
            JLabel(text("CD_LOC")), contentPane, padding = Insets(10, 15, 0, 0),
            alignment = GridBagConstraints.EAST,
            gridY = 1,
        )
        gbAdd(
            txtFolderPath, contentPane, padding = Insets(10, 10, 0, 15),
            alignment = GridBagConstraints.WEST,
            gridX = 1,
            gridY = 1,
            horizontalFill = true
        )
        gbAdd(
            v1, contentPane, padding = Insets(0, 0, 0, 15),
            alignment = GridBagConstraints.SOUTH,
            gridY = 0,
            gridX = 2,
            rowSpan = 2,
        )
        gbAdd(
            h1,
            contentPane,
            gridX = 0,
            gridY = 3,
            alignment = GridBagConstraints.WEST,
            colSpan = 3,
            horizontalFill = true,
        )
        rootPane.defaultButton = btnOpenFolder

        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnOpenFolder.requestFocusInWindow()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })
    }
}