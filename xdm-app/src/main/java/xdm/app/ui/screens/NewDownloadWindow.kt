package xdm.app.ui.screens

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.DuplicateKind
import xdm.app.RecordStatus
import xdm.app.ui.components.AppMenuHandler
import xdm.app.ui.components.CategoryStyle
import xdm.app.I8N.text
import xdm.app.utils.chooseFile
import xdm.app.utils.isAutoCategorySelected
import xdm.app.utils.persistFolderChoiceOnChange
import xdm.app.utils.populateSaveInFolders
import xdm.app.utils.rememberFolderChoice
import xdm.app.utils.selectedBaseFolder
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.getClipBoardText
import xdm.app.utils.sameWidth
import xdm.app.utils.validateURL
import xdm.core.downloaders.HttpDownloadTaskInfo
import xdm.core.util.*
import xdm.core.util.CoreUtils.uniqueId
import xdm.integration.EventChannel
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.math.max

class NewDownloadWindow : JDialog() {
    private lateinit var txtUrl: JTextField
    private lateinit var txtFileName: JTextField
    private lateinit var cmbSaveIn: JComboBox<String>
    private lateinit var btnDownload: JButton
    private lateinit var btnLater: JButton
    private lateinit var lblIgnore: JLabel
    private lateinit var cmbSegments: JComboBox<Int>
    private lateinit var modelSaveIn: DefaultComboBoxModel<String>
    private lateinit var lblFileInfo: JLabel
    private lateinit var originalFileName: String
    private lateinit var lbAddress: JLabel

    private var taskInfo: HttpDownloadTaskInfo? = null
    private var selectedFolder: String? = null

    init {
        initUI()
        attachUrlChangeListener()
        attachFileNameChangeListener()
        defaultCloseOperation = DISPOSE_ON_CLOSE
    }

    private fun initUI() {
        isAlwaysOnTop = true
        title = text("ND_TITLE")
        val gridBagLayout = GridBagLayout().apply {
            columnWidths = intArrayOf(0, 0, 0, 0, 0, 0, 0)
            rowHeights = intArrayOf(0, 0, 0, 0, 0, 0)
            columnWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, Double.MIN_VALUE)
            rowWeights = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, Double.MIN_VALUE)
        }
        contentPane.layout = gridBagLayout
        //contentPane.background = UIManager.getColor("Table.background")

        lbAddress = JLabel(text("ND_ADDRESS"))
        lbAddress.horizontalAlignment = SwingConstants.RIGHT
        val gbcLbAddress = GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(15, 20, 5, 10)
            gridx = 0
            gridy = 0
        }
        contentPane.add(lbAddress, gbcLbAddress)

        txtUrl = JTextField()
        txtUrl.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        val gbcTxtUrl = GridBagConstraints().apply {
            gridwidth = 4
            insets = Insets(15, 0, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 1
            gridy = 0
        }
        contentPane.add(txtUrl, gbcTxtUrl)
        txtUrl.columns = 30

        val lblFile = JLabel(text("ND_FILE"))
        lblFile.horizontalAlignment = SwingConstants.RIGHT
        val gbcLblFile = GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(5, 20, 5, 10)
            gridx = 0
            gridy = 1
        }
        contentPane.add(lblFile, gbcLblFile)

        txtFileName = JTextField()
        txtFileName.putClientProperty(FlatClientProperties.STYLE, "arc: 10")
        val gbcTxtFileName = GridBagConstraints().apply {
            gridwidth = 4
            weightx = 1.0
            insets = Insets(5, 0, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 1
            gridy = 1
        }
        contentPane.add(txtFileName, gbcTxtFileName)
        txtFileName.columns = 10

        lblFileInfo = JLabel().apply {
            icon = createIcon(RemixIcon.FILE_LINE, 36, Color.GRAY)
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalTextPosition = SwingConstants.CENTER
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            text = "---"
            preferredSize = Dimension(
                preferredSize.width + 30, preferredSize.height
            )
        }

        val gbcLblFileInfo = GridBagConstraints().apply {
            insets = Insets(20, 5, 5, 10)
            gridheight = 3
            gridx = 5
            gridy = 0
        }
        contentPane.add(lblFileInfo, gbcLblFileInfo)

        val lblSaveIn = JLabel(text("LBL_SAVE_IN"))
        lblSaveIn.horizontalAlignment = SwingConstants.RIGHT
        val gbcLblSaveIn = GridBagConstraints().apply {
            anchor = GridBagConstraints.EAST
            insets = Insets(5, 20, 5, 10)
            gridx = 0
            gridy = 2
        }
        contentPane.add(lblSaveIn, gbcLblSaveIn)

        modelSaveIn = DefaultComboBoxModel()
        cmbSaveIn = JComboBox(modelSaveIn)
        val gbcCmbSaveIn = GridBagConstraints().apply {
            gridwidth = 3
            weightx = 1.0
            insets = Insets(5, 0, 5, 5)
            fill = GridBagConstraints.HORIZONTAL
            gridx = 1
            gridy = 2
        }
        contentPane.add(cmbSaveIn, gbcCmbSaveIn)

        val btnBrowse = JButton(createIcon(RemixIcon.FOLDER_FILL, 16, Color.GRAY))
        val gbcBtnBrowse = GridBagConstraints().apply {
            insets = Insets(5, 0, 5, 5)
            gridx = 4
            gridy = 2
        }
        contentPane.add(btnBrowse, gbcBtnBrowse)

        val lblFreeSpace = JLabel("---").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }

        lblIgnore = JLabel(text("ND_IGNORE_PAGE")).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = UIManager.getColor("ProgressBar.foreground")
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    ignoreHost()
                }
            })
        }

        // Glue rather than a fixed gap: the link stays on the bottom edge as the dialog is resized.
        val infoBox = Box.createVerticalBox().apply {
            add(lblFreeSpace)
            add(Box.createVerticalGlue())
            add(lblIgnore)
            add(Box.createRigidArea(Dimension(0, 10)))
        }
        val gbcInfoBox = GridBagConstraints().apply {
            weighty = 1.0
            fill = GridBagConstraints.VERTICAL
            anchor = GridBagConstraints.NORTHWEST
            gridwidth = 4
            insets = Insets(5, 0, 5, 5)
            gridx = 1
            gridy = 3
        }
        contentPane.add(infoBox, gbcInfoBox)

        val panel = JPanel()
        panel.background = UIManager.getColor("Table.background")
        panel.border = EmptyBorder(10, 15, 10, 15)
        val gcPanel = GridBagConstraints().apply {
            weightx = 1.0
            gridwidth = 6
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 4
        }
        contentPane.add(panel, gcPanel)
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        panel.add(JLabel(text("ND_SEGMENTS")))
        panel.add(Box.createRigidArea(Dimension(10, 30)))

        cmbSegments = JComboBox(arrayOf(1, 2, 4, 8, 16, 32, 64)).apply {
            selectedItem = AppContext.config.maxSegments
            maximumSize = preferredSize
        }
        panel.add(cmbSegments)

        panel.add(Box.createHorizontalGlue())
        val rigidArea1 = Box.createRigidArea(Dimension(10, 20))
        panel.add(rigidArea1)

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }
        panel.add(btnCancel)

        panel.add(Box.createRigidArea(Dimension(10, 30)))

        btnLater = JButton(text("ND_DOWNLOAD_LATER"))
        btnLater.addActionListener { downloadLater() }
        panel.add(btnLater)

        val rigidArea = Box.createRigidArea(Dimension(10, 30))
        panel.add(rigidArea)

        btnDownload = JButton(text("ND_DOWNLOAD"))
        btnDownload.addActionListener { addDownload(now = true) }
        panel.add(btnDownload)

        getRootPane().defaultButton = btnDownload

        //sameWidth(btnDownload, btnCancel, btnLater)

        addWindowListener(
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) {
                    btnDownload.requestFocusInWindow()
                    requestFocus()
                }

                override fun windowClosed(e: WindowEvent) {
                    System.gc()
                }
            })

        btnBrowse.addActionListener {
            val selected = chooseFile(
                this@NewDownloadWindow,
                directoriesOnly = true,
                currentDir = File(selectedBaseFolder(cmbSaveIn))
            )
            if (selected != null) {
                val path = selected.absolutePath
                val idx = modelSaveIn.getIndexOf(path).takeIf { it >= 0 } ?: run {
                    modelSaveIn.addElement(path)
                    modelSaveIn.size - 1
                }
                cmbSaveIn.selectedIndex = idx
            }
        }

        persistFolderChoiceOnChange(cmbSaveIn)
        cmbSaveIn.addItemListener {
            Logger.info("Selected item: ${cmbSaveIn.selectedItem}")
            val folder = selectedBaseFolder(cmbSaveIn)
            if (selectedFolder != folder) {
                selectedFolder = folder
                val freeSpace = File(folder).freeSpace
                lblFreeSpace.text = "${text("MSG_FREE_SPACE")} ${FormatHelper.formatSize(freeSpace.toDouble())}"
            }
        }
    }

    //  private void downloadNow() {
    //    var url = txtUrl.getText();
    //    var file = txtFileName.getText();
    //    if (StringUtils.isNullOrEmptyOrBlank(url)) {
    //      JOptionPane.showMessageDialog(this, text("MSG_NO_URL"));
    //      return;
    //    }
    //    if (!XDMUtils.validateURL(url)) {
    //      JOptionPane.showMessageDialog(this, text("MSG_INVALID_URL"));
    //      return;
    //    }
    //    if (StringUtils.isNullOrEmptyOrBlank(file)) {
    //      JOptionPane.showMessageDialog(this, text("MSG_NO_FILE"));
    //      return;
    //    }
    //    var keepFileName = !StringUtils.equalsIgnoreCase(txtFileName.getText(), originalFileName);
    //    HttpSource source =
    //        HttpSource.builder()
    //            .id(UniqueID.get())
    //            .url(url)
    //            .fileName(file)
    //            .autoSelectFolder(cmbSaveIn.getSelectedIndex() == 0)
    //            .keepFileName(keepFileName)
    //            .folder(
    //                cmbSaveIn.getSelectedIndex() > 0
    //                    ? cmbSaveIn.getItemAt(cmbSaveIn.getSelectedIndex())
    //                    : null)
    //            .build();
    //    if (downloadInfo != null) {
    //      if (downloadInfo.getRequestHeaders() != null) {
    //        source.setHeaders(new HeaderCollection(downloadInfo.getRequestHeaders()));
    //      }
    //      if (downloadInfo.getCookie() != null) {
    //        source.setCookies(downloadInfo.getCookie());
    //      }
    //    }
    //    AppContext.INSTANCE.getDownloader().startDownload(source, true, -1);
    //    dispose();
    //  }
    /** Registers the download, started right away when [now] is set and left paused otherwise. */
    private fun addDownload(now: Boolean) {
        if (!confirmNotDuplicate()) return
        if (createDownload(now) != null) dispose()
    }

    /**
     * Looks the entered download up among the existing ones and, on a match, asks what to do.
     * Returns true to go on adding it; false otherwise. Cancelling, or handling it through the
     * existing download (resuming it or opening its folder), also closes this dialog.
     * The browser's size and ETag describe the captured URL only, so they are dropped once the
     * user edits the address.
     */
    private fun confirmNotDuplicate(): Boolean {
        val url = txtUrl.text.trim()
        if (url.isEmpty()) return true
        val captured = taskInfo?.takeIf { it.url == url }
        val match = AppContext.downloader.duplicates.find(
            url, txtFileName.text.trim(), captured?.knownFileSize, captured?.etag
        ) ?: return true
        val rec = match.record

        val details = buildList {
            add(text("PROP_NAME") to rec.fileName)
            if (rec.size > 0) add(text("PROP_SIZE") to FormatHelper.formatSize(rec.size.toDouble()))
            add(text("PROP_STATUS") to statusText(rec.status))
        }
        val message = arrayOf(
            text(if (match.kind == DuplicateKind.SAME_URL) "MSG_DUP_SAME" else "MSG_DUP_LIKELY"),
            duplicateDetailsPanel(details),
            text("MSG_DUP_ASK")
        )
        val again = text("DUP_DOWNLOAD_AGAIN")
        val existing = when (rec.status) {
            RecordStatus.FINISHED -> text("CTX_OPEN_FOLDER")
            RecordStatus.PAUSED, RecordStatus.ERROR -> text("DUP_RESUME_EXISTING")
            else -> null
        }
        val options = listOfNotNull(again, existing, text("ND_CANCEL")).toTypedArray()
        val choice = JOptionPane.showOptionDialog(
            this, message, text("DUP_TITLE"), JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE, null, options, options.last()
        )
        return when {
            choice == 0 -> true
            existing != null && choice == 1 -> {
                if (rec.status == RecordStatus.FINISHED) {
                    AppMenuHandler.openFolder(rec, this)
                } else {
                    AppContext.downloader.resumeDownload(rec.id)
                }
                dispose()
                false
            }
            // Cancel drops the download altogether; closing the prompt (Esc, title bar) goes back to the form.
            choice == options.lastIndex -> {
                dispose()
                false
            }
            else -> false
        }
    }

    /**
     * Lays the existing download's details out as label/value rows. Values wrap at a fixed width, so a
     * long file name grows the prompt downwards rather than sideways; they also stay selectable for copying.
     */
    private fun duplicateDetailsPanel(rows: List<Pair<String, String>>): JPanel {
        val valueWidth = 360
        val panel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = EmptyBorder(8, 0, 8, 0)
        }
        rows.forEachIndexed { i, (label, value) ->
            val top = if (i == 0) 0 else 6
            panel.add(JLabel("$label:").apply {
                foreground = UIManager.getColor("Label.disabledForeground")
            }, GridBagConstraints().apply {
                gridx = 0; gridy = i; anchor = GridBagConstraints.FIRST_LINE_START
                insets = Insets(top, 0, 0, 12)
            })
            val valueArea = JTextArea(value).apply {
                isEditable = false
                lineWrap = true
                isOpaque = false
                border = null
                font = UIManager.getFont("Label.font")
                foreground = UIManager.getColor("Label.foreground")
                // Sizing to the wrap width first makes the preferred height account for wrapped lines.
                setSize(valueWidth, Short.MAX_VALUE.toInt())
            }
            panel.add(valueArea, GridBagConstraints().apply {
                gridx = 1; gridy = i; anchor = GridBagConstraints.FIRST_LINE_START
                fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
                insets = Insets(top, 0, 0, 0)
            })
        }
        return panel
    }

    /**
     * Queues the download without starting it, offering the scheduler on the way: answering yes goes
     * on to [scheduleDownload], no leaves the download paused until it is started by hand.
     */
    private fun downloadLater() {
        if (!confirmNotDuplicate()) return
        when (
            JOptionPane.showConfirmDialog(
                this, text("MSG_ASK_SCHEDULE"), text("ND_DOWNLOAD_LATER"), JOptionPane.YES_NO_CANCEL_OPTION
            )
        ) {
            JOptionPane.YES_OPTION -> scheduleDownload()
            JOptionPane.NO_OPTION -> addDownload(now = false)
            else -> Unit
        }
    }

    /**
     * Adds the download in a paused state and asks the user when it should run. Cancelling the
     * scheduler leaves nothing behind: the just-created download is removed again.
     */
    private fun scheduleDownload() {
        val id = createDownload(now = false) ?: return
        val scheduled = ScheduleWindow(this, id).showDialog()
        if (!scheduled) {
            AppContext.downloader.deleteDownload(id, false)
            return
        }
        dispose()
    }

    /**
     * Validates the form and registers the download, started right away when [now] is set and left
     * paused otherwise. Returns the new download id, or null if the form is incomplete.
     */
    private fun createDownload(now: Boolean): Long? {
        val url = txtUrl.text
        val file = txtFileName.text
        if (StringUtils.isNullOrEmptyOrBlank(url)) {
            JOptionPane.showMessageDialog(this, text("MSG_NO_URL"))
            return null
        }
        if (!validateURL(url)) {
            JOptionPane.showMessageDialog(this, text("MSG_INVALID_URL"))
            return null
        }

        if (StringUtils.isNullOrEmptyOrBlank(file)) {
            JOptionPane.showMessageDialog(this, text("MSG_NO_FILE"))
            return null
        }

        val fileRenamedByUser = !StringUtils.equalsIgnoreCase(txtFileName.text, originalFileName)
        val task = HttpDownloadTaskInfo(
            id = uniqueId(),
            url = url,
            fileName = file,
            respectFileName = fileRenamedByUser,
            cookie = taskInfo?.cookie,
            headers = taskInfo?.headers,
            origin = taskInfo?.origin,
            autoCategorize = isAutoCategorySelected(cmbSaveIn),
            defaultDownloadFolder = selectedBaseFolder(cmbSaveIn),
            userSelectedDownloadFolder = null,
            maxPiece = cmbSegments.selectedItem as Int,
            authInfo = null,
            knownFileSize = taskInfo?.knownFileSize,
            // The browser's ETag belongs to the URL it captured, not to an address typed over it.
            etag = taskInfo?.etag?.takeIf { taskInfo?.url == url },
        )

        rememberFolderChoice(cmbSaveIn)
        AppContext.downloader.startHttpDownload(task, now)
        return task.id
    }

    /** Packing first is what makes the window insets known, so the content is never clipped. */
    private fun adjustSize() {
        pack()
        size = Dimension(max(width, 500), max(height, 270))
    }

    //  public void showWindow(final HttpMetadata metadata) {
    //    this.adjustSize();
    //    this.setLocationRelativeTo(null);
    //    modelSaveIn.addAll(AppContext.INSTANCE.getConfig().getRecentFolders());
    //    if (AppContext.INSTANCE.getConfig().isAutoSelectFolder()) {
    //      cmbSaveIn.setSelectedIndex(0);
    //    } else {
    //      cmbSaveIn.setSelectedIndex(AppContext.INSTANCE.getConfig().getFolderIndex() + 1);
    //    }
    //    if (metadata == null) {
    //      var url = PlatformUtils.getClipBoardText();
    //      if (!StringUtils.isNullOrEmptyOrBlank(url)) {
    //        txtUrl.setText(url);
    //      }
    //    } else {
    //      this.metadata = metadata;
    //    }
    //    this.setVisible(true);
    //  }
    //  public void showWindow(final BrowserDownloadInfo downloadInfo) {
    //    this.adjustSize();
    //    this.setLocationRelativeTo(null);
    //    modelSaveIn.removeAllElements();
    //    modelSaveIn.addAll(AppContext.INSTANCE.getConfig().getRecentFolders());
    //    if (AppContext.INSTANCE.getConfig().isAutoSelectFolder()) {
    //      cmbSaveIn.setSelectedIndex(0);
    //    } else {
    //      cmbSaveIn.setSelectedIndex(AppContext.INSTANCE.getConfig().getFolderIndex() + 1);
    //    }
    //    if (downloadInfo == null) {
    //      var url = PlatformUtils.getClipBoardText();
    //      if (url != null && XDMUtils.validateURL(url)) {
    //        txtUrl.setText(url);
    //      }
    //    } else {
    //      this.downloadInfo = downloadInfo;
    //      this.txtUrl.setText(downloadInfo.getUrl());
    //      this.txtFileName.setText(downloadInfo.getFileName());
    //      if (this.downloadInfo.getFileName() != null) {
    //        this.originalFileName = downloadInfo.getFileName();
    //      }
    //      var sz = downloadInfo.getFileSize();
    //      if (sz != null) {
    //        this.lblFileInfo.setText(FormatUtilities.formatSize(sz));
    //      }
    //    }
    //    this.setVisible(true);
    //  }
    private fun ignoreHost() {
        val url = taskInfo?.url ?: txtUrl.text
        val host = try {
            URI(url.trim()).host
        } catch (e: Exception) {
            Logger.info(e)
            null
        }
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

    fun showWindow(taskInfo: HttpDownloadTaskInfo?) {
        lblIgnore.isVisible = taskInfo != null
        cmbSegments.selectedItem = AppContext.config.maxSegments
        populateSaveInFolders(modelSaveIn, cmbSaveIn)
        if (taskInfo == null) {
            val url = getClipBoardText()
            if (url != null && validateURL(url)) {
                txtUrl.text = url
            }
            this.taskInfo = null
        } else {
            this.taskInfo = taskInfo
            this.txtUrl.text = taskInfo.url
            this.txtFileName.text = taskInfo.fileName
            this.originalFileName = taskInfo.fileName
            this.lblFileInfo.text = taskInfo.knownFileSize?.let {
                FormatHelper.formatSize(it.toDouble())
            } ?: "---"
        }

        // Sized last: the ignore link is only part of the layout for browser-captured downloads.
        this.adjustSize()
        this.setLocationRelativeTo(null)
        this.isVisible = true
    }

    /** Picks the icon the file's category uses, so the preview matches the row it will become. */
    private fun updateFileIcon() {
        val glyph = CategoryStyle.lineVariant(CategoryStyle.iconForFile(txtFileName.text.orEmpty()))
        lblFileInfo.icon = createIcon(glyph, 36, Color.GRAY)
    }

    private fun urlUpdated(e: DocumentEvent) {
        try {
            val doc = e.document
            val len = doc.length
            val text = doc.getText(0, len)
            txtFileName.text = FileUtils.getFileName(text)
            originalFileName = txtFileName.text
        } catch (err: Exception) {
            Logger.info(err)
        }
    }

    private fun attachFileNameChangeListener() {
        txtFileName.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateFileIcon()

            override fun removeUpdate(e: DocumentEvent) = updateFileIcon()

            override fun changedUpdate(e: DocumentEvent) = updateFileIcon()
        })
    }

    private fun attachUrlChangeListener() {
        txtUrl
            .document
            .addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) {
                        urlUpdated(e)
                    }

                    override fun removeUpdate(e: DocumentEvent) {
                        urlUpdated(e)
                    }

                    override fun changedUpdate(e: DocumentEvent) {
                        urlUpdated(e)
                    }
                })
    }
}
