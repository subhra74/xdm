package xdm.app.ui.screens

import xdm.app.I8N.text
import xdm.app.ui.components.MessageBox
import xdm.app.utils.gbAdd
import xdm.app.utils.openFileExternal
import xdm.app.utils.openFolderExternal
import xdm.core.util.Logger
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.FileNotFoundException
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.UIManager
import javax.swing.WindowConstants

class DownloadCompleteWindow : JDialog() {
    private val txtFolderPath = JTextField().apply { isEditable = false }
    private val txtFileName = JTextField().apply { isEditable = false }
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

    fun setDetails(file: String, folder: String) {
        txtFolderPath.text = folder
        txtFileName.text = file
    }

    init {
        isAlwaysOnTop = true
        title = text("CD_TITLE")
        size = Dimension(430, 210)
        setLocationRelativeTo(null)
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val b1 = Box.createHorizontalBox().apply {
            border = BorderFactory.createEmptyBorder(10, 15, 10, 15)
            add(lblSkipNotification)
            add(Box.createHorizontalGlue())
            add(btnOpenFile)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(btnOpenFolder)
            isOpaque = true
            background = UIManager.getColor("Table.background")
        }

        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 1.0)
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
            b1,
            contentPane,
            gridX = 0,
            gridY = 3,
            alignment = GridBagConstraints.WEST,
            colSpan = 2,
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