package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ui.screens.settings.settingsIconButton
import xdm.app.utils.RemixIcon
import xdm.app.utils.gbAdd
import xdm.app.utils.openWebPage
import xdm.app.utils.sameWidth
import xdm.core.network.http.HeaderMap
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

/**
 * "Refresh link" for an HTTP download. Offers two ways to hand the download a fresh link, chosen
 * in the dialog itself: replay the capture from the browser, or type the address and headers by
 * hand. When the download has no page to reopen ([refererPage] is null) only the manual side is
 * available.
 *
 * The dialog stays non-modal: the browser path needs the integration server to keep delivering
 * captures into the running app while this window is up.
 */
class RefreshLinkWindow(
    parent: Window?,
    val id: Long,
    private val refererPage: String?,
) : JDialog(parent) {

    private val radioBrowser = JRadioButton(text("REF_MODE_BROWSER"))
    private val radioManual = JRadioButton(text("REF_MODE_MANUAL"))
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val txtUrl = JTextField()

    /**
     * Header rows in display order, one row per value. A `Cookie` row carries the whole cookie
     * string: the engine merges the task's cookie field with any `Cookie` header, so the editor
     * shows and writes back exactly one of them (see [collectHeaders]).
     */
    private val headerRows = mutableListOf<Pair<String, String>>()
    private val headerModel = HeaderTableModel()
    private val headerTable = JTable(headerModel)

    private lateinit var btnEditHeader: JButton
    private lateinit var btnDeleteHeader: JButton
    private val btnApply = JButton(text("REF_APPLY"))
    private val lblWaiting = JLabel(text("REF_WAITING_FOR_LINK")).apply {
        alignmentX = LEFT_ALIGNMENT
        isVisible = false
    }

    init {
        title = text("MENU_REFRESH_LINK")
        defaultCloseOperation = DISPOSE_ON_CLOSE
        isAlwaysOnTop = true

        loadTaskInfo()
        initUI()
        syncMode()

        size = Dimension(560, 500)
        setLocationRelativeTo(parent)
    }

    /** Seeds the address and the header rows from the download's stored task info. */
    private fun loadTaskInfo() {
        val info = AppContext.taskInfoDB.getHttpTask(id) ?: return
        txtUrl.text = info.url

        val cookies = mutableListOf<String>()
        info.headers?.forEach { (name, values) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                cookies.addAll(values.filter { it.isNotBlank() })
            } else {
                values.forEach { headerRows.add(name to it) }
            }
        }
        info.cookie?.takeIf { it.isNotBlank() }?.let { cookies.add(it) }
        // One row for everything the engine would have joined into a single Cookie header.
        if (cookies.isNotEmpty()) headerRows.add("Cookie" to cookies.joinToString("; "))
    }

    private fun initUI() {
        val mainPanel = JPanel(GridBagLayout())
        mainPanel.border = EmptyBorder(15, 20, 10, 20)

        val lblTitle = JLabel(text("REF_TITLE")).apply { font = font.deriveFont(Font.BOLD) }
        gbAdd(
            lblTitle, mainPanel,
            gridX = 0, gridY = 0, weightX = 1.0,
            padding = Insets(0, 0, 10, 0),
            horizontalFill = true
        )

        val modeGroup = ButtonGroup()
        modeGroup.add(radioBrowser)
        modeGroup.add(radioManual)
        radioBrowser.isEnabled = refererPage != null
        if (refererPage != null) radioBrowser.isSelected = true else radioManual.isSelected = true

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(radioBrowser)
            add(Box.createRigidArea(Dimension(20, 0)))
            add(radioManual)
        }
        gbAdd(
            modePanel, mainPanel,
            gridX = 0, gridY = 1, weightX = 1.0,
            padding = Insets(0, 0, 10, 0),
            horizontalFill = true
        )

        cardPanel.add(buildBrowserPanel(), CARD_BROWSER)
        cardPanel.add(buildManualPanel(), CARD_MANUAL)
        // The card panel absorbs the leftover height, so the header table grows with the dialog.
        mainPanel.add(cardPanel, GridBagConstraints().apply {
            gridx = 0; gridy = 2
            weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
            insets = Insets(0, 0, 5, 0)
        })

        contentPane.add(mainPanel, BorderLayout.CENTER)

        radioBrowser.addActionListener { syncMode() }
        radioManual.addActionListener { syncMode() }

        val btnBar = JPanel().apply {
            background = UIManager.getColor("Table.background")
            border = EmptyBorder(10, 15, 10, 15)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }
        btnApply.addActionListener { onApply() }
        sameWidth(btnCancel, btnApply)
        rootPane.defaultButton = btnApply

        btnBar.add(Box.createHorizontalGlue())
        btnBar.add(btnCancel)
        btnBar.add(Box.createRigidArea(Dimension(10, 0)))
        btnBar.add(btnApply)
        contentPane.add(btnBar, BorderLayout.SOUTH)
    }

    private fun buildBrowserPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = EmptyBorder(10, 5, 10, 5)

        panel.add(wrappedLabel(text("REF_DESC1")))
        panel.add(Box.createRigidArea(Dimension(0, 12)))

        val btnOpen = JButton(text("REF_OPEN_PAGE"))
        btnOpen.alignmentX = LEFT_ALIGNMENT
        // Nothing is on its way until the page has actually been opened, so the status line
        // only appears once the user has sent the browser there.
        btnOpen.addActionListener {
            refererPage?.let { openWebPage(it) }
            lblWaiting.isVisible = true
        }
        panel.add(btnOpen)

        panel.add(Box.createRigidArea(Dimension(0, 12)))
        panel.add(lblWaiting)
        panel.add(Box.createVerticalGlue())
        return panel
    }

    private fun buildManualPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(10, 5, 10, 5)

        if (refererPage == null) {
            gbAdd(
                wrappedLabel(text("REF_NO_PAGE")), panel,
                gridX = 0, gridY = 0, colSpan = 2, weightX = 1.0,
                padding = Insets(0, 0, 10, 0),
                horizontalFill = true
            )
        }

        gbAdd(
            JLabel(text("REF_ADDRESS")), panel,
            gridX = 0, gridY = 1, colSpan = 2,
            padding = Insets(0, 0, 4, 0)
        )
        gbAdd(
            txtUrl, panel,
            gridX = 0, gridY = 2, colSpan = 2, weightX = 1.0,
            padding = Insets(0, 0, 12, 0),
            horizontalFill = true
        )

        gbAdd(
            JLabel(text("REF_HEADERS")), panel,
            gridX = 0, gridY = 3, weightX = 1.0,
            padding = Insets(0, 0, 4, 0),
            horizontalFill = true
        )

        val actions = Box.createHorizontalBox().apply {
            add(settingsIconButton(RemixIcon.ADD_LINE, text("REF_HDR_ADD")) { addHeader() })
            btnEditHeader = settingsIconButton(RemixIcon.EDIT_LINE, text("REF_HDR_EDIT")) { editSelectedHeader() }
            add(btnEditHeader)
            btnDeleteHeader = settingsIconButton(RemixIcon.DELETE_BIN_LINE, text("REF_HDR_DELETE")) { deleteSelectedHeader() }
            add(btnDeleteHeader)
        }
        gbAdd(
            actions, panel,
            gridX = 1, gridY = 3,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 0, 4, 0)
        )

        headerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        headerTable.rowHeight = 24
        headerTable.selectionModel.addListSelectionListener { syncHeaderButtons() }
        headerTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) editSelectedHeader()
            }
        })

        val scroll = JScrollPane(headerTable)
        scroll.preferredSize = Dimension(400, 180)
        panel.add(scroll, GridBagConstraints().apply {
            gridx = 0; gridy = 4; gridwidth = 2
            weightx = 1.0; weighty = 1.0
            fill = GridBagConstraints.BOTH
        })

        syncHeaderButtons()
        return panel
    }

    private fun wrappedLabel(body: String) = JLabel("<html><body style='width:420px'>$body</body></html>").apply {
        alignmentX = LEFT_ALIGNMENT
    }

    /** Shows the card for the selected mode; applying a link only makes sense in manual mode. */
    private fun syncMode() {
        val manual = radioManual.isSelected
        cardLayout.show(cardPanel, if (manual) CARD_MANUAL else CARD_BROWSER)
        btnApply.isEnabled = manual
    }

    private fun syncHeaderButtons() {
        val hasSelection = headerTable.selectedRow >= 0
        btnEditHeader.isEnabled = hasSelection
        btnDeleteHeader.isEnabled = hasSelection
    }

    private fun addHeader() {
        HeaderEditDialog(this, null).showDialog()?.let {
            headerRows.add(it)
            headerModel.fireTableDataChanged()
        }
    }

    private fun editSelectedHeader() {
        val row = headerTable.selectedRow
        if (row < 0) return
        HeaderEditDialog(this, headerRows[row]).showDialog()?.let {
            headerRows[row] = it
            headerModel.fireTableRowsUpdated(row, row)
        }
    }

    private fun deleteSelectedHeader() {
        val row = headerTable.selectedRow
        if (row < 0) return
        headerRows.removeAt(row)
        headerModel.fireTableDataChanged()
        syncHeaderButtons()
    }

    /**
     * Splits the edited rows into the header map and the cookie string. Cookie rows are pulled out
     * of the map so the engine cannot merge them with a stale cookie: whatever the user left in
     * the Cookie rows replaces the download's cookie outright, and removing them clears it.
     */
    private fun collectHeaders(): Pair<HeaderMap?, String?> {
        val headers = linkedMapOf<String, MutableList<String>>()
        val cookies = mutableListOf<String>()
        headerRows.forEach { (name, value) ->
            if (name.equals("Cookie", ignoreCase = true)) {
                if (value.isNotBlank()) cookies.add(value)
            } else {
                headers.getOrPut(name) { mutableListOf() }.add(value)
            }
        }
        val headerMap: HeaderMap? = if (headers.isEmpty()) null else headers.mapValues { it.value.toList() }
        return headerMap to cookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun onApply() {
        val url = txtUrl.text.trim()
        if (!isValidUrl(url)) {
            JOptionPane.showMessageDialog(
                this, text("ERR_REF_INVALID_URL"), title, JOptionPane.WARNING_MESSAGE
            )
            return
        }
        val (headers, cookie) = collectHeaders()
        AppContext.downloader.updateDownloadLink(id, url, headers, cookie)
        dispose()
    }

    private fun isValidUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        return runCatching {
            val scheme = URI(url).scheme
            scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)
    }

    private inner class HeaderTableModel : AbstractTableModel() {
        override fun getRowCount() = headerRows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) =
            if (column == 0) text("REF_HDR_NAME") else text("REF_HDR_VALUE")

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val (name, value) = headerRows[rowIndex]
            return if (columnIndex == 0) name else value
        }
    }

    companion object {
        private const val CARD_BROWSER = "BROWSER"
        private const val CARD_MANUAL = "MANUAL"
    }
}

/** Modal name/value editor for a single request header. */
private class HeaderEditDialog(parent: Window, existing: Pair<String, String>?) :
    JDialog(parent, text("REF_HDR_TITLE"), ModalityType.APPLICATION_MODAL) {

    private val txtName = JTextField(existing?.first ?: "", 20)
    private val txtValue = JTextField(existing?.second ?: "", 20)
    private var result: Pair<String, String>? = null

    init {
        val panel = JPanel(GridBagLayout())
        panel.border = EmptyBorder(15, 20, 10, 20)

        gbAdd(
            JLabel(text("REF_HDR_NAME")), panel,
            gridX = 0, gridY = 0,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 0, 8, 8)
        )
        gbAdd(
            txtName, panel,
            gridX = 1, gridY = 0, weightX = 1.0,
            padding = Insets(0, 0, 8, 0),
            horizontalFill = true
        )
        gbAdd(
            JLabel(text("REF_HDR_VALUE")), panel,
            gridX = 0, gridY = 1,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 0, 8, 8)
        )
        gbAdd(
            txtValue, panel,
            gridX = 1, gridY = 1, weightX = 1.0,
            padding = Insets(0, 0, 8, 0),
            horizontalFill = true
        )
        contentPane.add(panel, BorderLayout.CENTER)

        val btnBar = JPanel().apply {
            background = UIManager.getColor("Table.background")
            border = EmptyBorder(10, 15, 10, 15)
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }
        val btnOk = JButton(text("REF_HDR_OK"))
        btnOk.addActionListener { onOk() }
        sameWidth(btnCancel, btnOk)
        rootPane.defaultButton = btnOk

        btnBar.add(Box.createHorizontalGlue())
        btnBar.add(btnCancel)
        btnBar.add(Box.createRigidArea(Dimension(10, 0)))
        btnBar.add(btnOk)
        contentPane.add(btnBar, BorderLayout.SOUTH)

        defaultCloseOperation = DISPOSE_ON_CLOSE
        pack()
        size = Dimension(maxOf(420, width), height)
        setLocationRelativeTo(parent)
    }

    private fun onOk() {
        val name = txtName.text.trim()
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, text("ERR_REF_HDR_NAME"), title, JOptionPane.WARNING_MESSAGE
            )
            return
        }
        result = name to txtValue.text.trim()
        dispose()
    }

    /** Shows the dialog modally; returns the edited header, or null when cancelled. */
    fun showDialog(): Pair<String, String>? {
        isVisible = true
        return result
    }
}
