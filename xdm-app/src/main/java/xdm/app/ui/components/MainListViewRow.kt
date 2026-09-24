package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import xdm.app.utils.showMenu
import xdm.core.util.FormatHelper.formatDateShort
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import xdm.core.util.Logger
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.CellEditorListener
import javax.swing.event.TableModelEvent
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

typealias ActionCallBack = (DbRecord) -> Unit

class MainListViewRow(
    private val table: JTable,
    private val model: MainListViewModel? = null
) :
    TableCellRenderer, TableCellEditor {
    private val panel: JPanel
    private val lblInfo: JLabel
    private val lblTitle: JLabel
    private val icon: JLabel
    private val lblProgress: JLabel
    private val panDetails: JPanel
    private val prg: JProgressBar
    private var viewRow = -1
    private var editEntry: DbRecord? = null
    private val gap = "  -  "
    private val icoUnchecked: Icon
    private val icoChecked: Icon
    private val btnOpenFolder: JButton
    private val btnPause: JButton
    private val btnResume: JButton
    private val btnDelete: JButton
    private val btnMenu: JButton
    private val pauseGap: Component
    private val resumeGap: Component
    private val openFolderGap: Component
    private val buttonContainer: Component

    var onPauseClick: ActionCallBack? = null
    var onResumeClick: ActionCallBack? = null
    var onOpenFileClick: ActionCallBack? = null
    var onOpenFolderClick: ActionCallBack? = null
    var onDeleteClick: ActionCallBack? = null
    var onMenuClick: ActionCallBack? = null

    private val iconBadge: JPanel
    /** Icon for a file that matches no category, and for the neutral pre-render state. */
    private val defaultIcon = createIcon(CategoryStyle.ALL_ICON, 16, Color.WHITE)

    /**
     * Category icons, cached per category. The key carries the glyph name so editing a
     * category's icon in settings invalidates the entry instead of showing the old glyph.
     */
    private val iconCache = HashMap<String, Icon>()

    init {
        model?.addTableModelListener { e: TableModelEvent ->
            val r = e.firstRow
            if (r >= table.rowCount) {
                return@addTableModelListener
            }
            val vr = table.convertRowIndexToView(r)
            if (vr == viewRow && viewRow != -1) {
                val ent = model.getValueAt(r, 0) as DbRecord?
                if (ent != null) {
                    Logger.info("XDM", "state $ent")
                    updateLabelText(ent, table.isRowSelected(vr))
                }
            }
        }

        panel = JPanel(BorderLayout(8, 5)).apply {
            if (AppContext.config.theme == "light") {
                background = UIManager.getColor("Table.background")
            }
        }
        val p4 = JPanel(FlowLayout())
        p4.isOpaque = false
        val p3 = JPanel(BorderLayout())
        p3.background = Color(30, 144, 255)
        iconBadge = p3

        icoUnchecked = createIcon(RemixIcon.CHECKBOX_BLANK_LINE, 16, Color.WHITE)
        icoChecked = createIcon(RemixIcon.CHECKBOX_LINE, 16, Color.WHITE)
//        icoFile = createIcon(RemixIcon.FILE_ZIP_FILL, 16, Color.WHITE)
        icon = JLabel(defaultIcon)
        icon.border = EmptyBorder(7, 7, 7, 7)
        //    icon.addMouseMotionListener(
        //        new MouseAdapter() {
        //          @Override
        //          public void mouseMoved(MouseEvent e) {
        //            if (editEntry != null && editEntry.isSelected()) {
        //              return;
        //            }
        //            icon.setIcon(icoUnchecked);
        //            //            var dim = icon.getSize();
        //            //            var r = new Rectangle(dim.width / 2 - 7, dim.height / 2 - 7, 14,
        // 14);
        //            //            if (r.contains(e.getPoint())) {
        //            //              icon.setIcon(icoChecked);
        //            //            } else {
        //            //              icon.setIcon(icoUnchecked);
        //            //            }
        //          }
        //        });
        icon.addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    val oldIcon = icon.icon
                    icon.icon = if (table.isRowSelected(viewRow)) icoChecked else icoUnchecked
                    icon.putClientProperty("old.icon", oldIcon)
                }

                override fun mouseExited(e: MouseEvent) {
                    val isSelectionMode = table.selectedRowCount > 0
                    icon.icon = if (table.isRowSelected(viewRow))
                        icoChecked
                    else
                        if (isSelectionMode) icoUnchecked else {
                            val oldIcon = icon.getClientProperty("old.icon")
                            if (oldIcon != null) {
                                oldIcon as Icon
                            } else {
                                defaultIcon
                            }
                        }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (viewRow == -1) {
                        return
                    }
                    val wasInSelectionMode = table.selectedRowCount > 0
                    table.changeSelection(viewRow, 0, true, false)
                    val isInSelectionMode = table.selectedRowCount > 0
                    icon.icon = if (table.isRowSelected(viewRow)) icoChecked else icoUnchecked
                    updateLabelText(editEntry!!, table.isRowSelected(viewRow))
                    if (wasInSelectionMode != isInSelectionMode && model != null) {
                        table.revalidate()
                        table.repaint()
                    }
                }
            })
        val dim = icon.preferredSize
        p3.putClientProperty(FlatClientProperties.STYLE, "arc: " + dim.width)

        // icon.setOpaque(true);
        // icon.setBackground(Color.ORANGE);
        p3.add(icon)

        //      var chk = new JCheckBox();
        //      chk.setBorder(new EmptyBorder(0, 0, 0, 8));
        //      p4.add(chk);
        p4.add(p3)

        // p3.setBorder(new EmptyBorder(5,5,5,5));
        val content = JPanel(GridLayout(2, 1, 0, 0))
        content.isOpaque = false

        lblTitle = JLabel("Some long title name for testing")
        lblTitle.cursor = Cursor(Cursor.HAND_CURSOR)
        lblTitle.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                if (editEntry != null) {
                    Logger.info(editEntry!!)
                    onOpenFileClick?.invoke(editEntry!!)
                }
            }
        })
        lblTitle.verticalAlignment = SwingConstants.BOTTOM
        val fnt = lblTitle.font.deriveFont(12.0f)
        lblInfo = JLabel("Some long title name for testing")
        lblInfo.verticalAlignment = SwingConstants.TOP
        lblInfo.font = fnt
        lblInfo.font = lblTitle.font.deriveFont(12.0f)
        lblInfo.foreground = Color.GRAY
        content.add(lblTitle)
        content.add(lblInfo)

        val p1 = JPanel(BorderLayout(0, 0)).apply {
            if (AppContext.config.theme == "light") {
                background = UIManager.getColor("Table.background")
            }
        }
        panDetails = JPanel(BorderLayout()).apply {
            if (AppContext.config.theme == "light") {
                background = UIManager.getColor("Table.background")
            }
        }
        panDetails.border = EmptyBorder(0, 0, 0, 10)
        prg = JProgressBar()
        prg.preferredSize = Dimension(60, 10)
        prg.alignmentY = Component.TOP_ALIGNMENT
        lblProgress = JLabel("Downloading 100 %")
        lblProgress.font = fnt
        // prg.setPreferredSize(new Dimension(lblProgress.getPreferredSize().width + 5, 10));
        panDetails.add(lblProgress)
        panDetails.add(prg, BorderLayout.SOUTH)
        p1.add(panDetails)
        prg.border = EmptyBorder(0, 0, 5, 0)

        val buttonContainer = Box.createHorizontalBox()
        buttonContainer.border = EmptyBorder(10, 0, 10, 0)

        btnPause =
            createButton(
                RemixIcon.PAUSE_CIRCLE_LINE, Color.GRAY
            ) {
                if (editEntry != null) {
                    Logger.info(editEntry!!)
                    onPauseClick?.invoke(editEntry!!)
                }
            }

        btnResume =
            createButton(
                RemixIcon.PLAY_CIRCLE_LINE, Color.GRAY
            ) {
                if (editEntry != null) {
                    Logger.info(editEntry)
                    onResumeClick?.invoke(editEntry!!)
                }
            }

        btnDelete =
            createButton(
                RemixIcon.DELETE_BIN_LINE, Color.GRAY
            ) {
                if (editEntry != null) {
                    onDeleteClick?.invoke(editEntry!!)
                }
            }

        btnMenu =
            createButton(
                RemixIcon.MORE_2_FILL
            ) {
                if (editEntry != null) {
                    onMenuClick?.invoke(editEntry!!)
                }
            }

        btnOpenFolder =
            createButton(
                RemixIcon.FOLDER_6_LINE, Color.GRAY
            ) {
                if (editEntry != null) {
                    onOpenFolderClick?.invoke(editEntry!!)
                }
            }

        pauseGap = Box.createRigidArea(Dimension(5, 10))
        resumeGap = Box.createRigidArea(Dimension(5, 10))
        openFolderGap = Box.createRigidArea(Dimension(5, 10))

        buttonContainer.add(btnPause)
        buttonContainer.add(pauseGap)
        buttonContainer.add(btnResume)
        buttonContainer.add(resumeGap)
        buttonContainer.add(btnOpenFolder)
        buttonContainer.add(openFolderGap)
        buttonContainer.add(btnDelete)
        buttonContainer.add(btnMenu)

        // buttonContainer.add(Box.createRigidArea(new Dimension(5, 10)));
        p1.add(buttonContainer, BorderLayout.EAST)
        p1.add(panDetails)
        p1.isOpaque = false
        p4.isOpaque = false

        panel.add(p4, BorderLayout.WEST)
        panel.add(content)
        panel.add(p1, BorderLayout.EAST)
        panel.border = EmptyBorder(0, 5, 5, 5)

        this.buttonContainer = buttonContainer
    }

    private fun createButton(
        iconName: RemixIcon,
        iconColor: Color = Color.GRAY,
        e: ActionListener
    ): JButton {
        val btn = JButton(createIcon(iconName, 16, iconColor))
        btn.putClientProperty("JButton.buttonType", "toolBarButton")
        btn.isFocusable = false
        btn.addActionListener(e)
        return btn
    }

    val height: Int
        get() = panel.preferredSize.height

    fun showPopupMenu(menu: JPopupMenu) {
        showMenu(btnMenu, menu)
    }

    private fun getStatusText(ent: DbRecord): String {
        val text = StringBuilder(80)
        text.append(formatDateShort(ent.date))
        if (ent.downloaded > 0) {
            text.append(gap).append(formatSize(ent.downloaded.toDouble()))
        }
        if (ent.size > 0) {
            text.append(" / ").append(formatSize(ent.size.toDouble()))
        }
        if (ent.speed > 0 && ent.status == RecordStatus.DOWNLOADING) {
            text.append(" (").append(formatSize(ent.speed.toDouble())).append("/s)")
        }
        if (ent.eta > 0 && ent.status == RecordStatus.DOWNLOADING) {
            text.append(gap).append(toLongEta(ent.eta)).append(" left")
        }
        return text.toString()
    }

    private fun updateLabelText(ent: DbRecord, isSelected: Boolean) {
        iconBadge.background = Color(30, 144, 255)//CategoryStyle.color(categoryFor(ent.fileName))
        icon.icon =
            if (isSelected) icoChecked else if (table.selectedRowCount > 0) icoUnchecked else getIcon(ent.fileName)
        buttonContainer.isVisible = table.selectedRowCount == 0
        btnOpenFolder.isVisible = ent.status == RecordStatus.FINISHED
        openFolderGap.isVisible = btnOpenFolder.isVisible
        btnPause.isVisible = ent.status == RecordStatus.DOWNLOADING || ent.status == RecordStatus.READY
                || ent.status == RecordStatus.ASSEMBLING || ent.status == RecordStatus.PUBLISHING
        pauseGap.isVisible = btnPause.isVisible
        btnResume.isVisible =
            ent.status != RecordStatus.FINISHED && ent.status != RecordStatus.DOWNLOADING
                    && ent.status != RecordStatus.READY && ent.status != RecordStatus.ASSEMBLING
                    && ent.status != RecordStatus.PUBLISHING
        resumeGap.isVisible = btnResume.isVisible
        if (ent.status == RecordStatus.FINISHED) {
            lblInfo.text = (formatDateShort(ent.date)
                    + gap
                    + formatSize(ent.size.toDouble()))
            lblTitle.text = ent.fileName
            prg.isVisible = false
            lblProgress.text = ""
        } else {
            lblInfo.text = getStatusText(ent)
            lblTitle.text = ent.fileName
            prg.value = ent.progress
            var prgText = text("STAT_DOWNLOADING")
            if (ent.status == RecordStatus.DOWNLOADING) {
                if (ent.progress > 0) {
                    prgText = String.format(
                        "%s %d%s", text("STAT_DOWNLOADING"), ent.progress, "%"
                    )
                }
                prg.isVisible = true
            } else if (ent.status == RecordStatus.PAUSED) {
                prgText = if (ent.progress > 0) {
                    String.format("%s %d%s", text("STAT_PAUSED"), ent.progress, "%")
                } else {
                    text("STAT_PAUSED")
                }
                prg.isVisible = false
            } else if (ent.status == RecordStatus.READY) {
                prgText = if (ent.progress > 0) {
                    String.format("%s %d%s", text("MSG_WAIT"), ent.progress, "%")
                } else {
                    text("MSG_WAIT")
                }
                prg.isVisible = true
            } else if (ent.status == RecordStatus.ASSEMBLING) {
                prgText = if (ent.progress > 0) {
                    String.format("%s %d%s", text("STAT_ASSEMBLING"), ent.progress, "%")
                } else {
                    text("STAT_ASSEMBLING")
                }
                prg.isVisible = true
            } else if (ent.status == RecordStatus.PUBLISHING) {
                prgText = if (ent.progress > 0) {
                    String.format("%s %d%s", text("STAT_PUBLISHING"), ent.progress, "%")
                } else {
                    text("STAT_PUBLISHING")
                }
                prg.isVisible = true
            }
            lblProgress.text = prgText
        }
    }

    private fun getComp(table: JTable?, value: DbRecord?, isSelected: Boolean): Component {
        value?.let { updateLabelText(it, isSelected) }
        table?.let { panel.background = it.background }
        return panel
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        this.editEntry = null
        this.viewRow = -1
        return getComp(table, value as DbRecord, isSelected)
    }

    override fun getTableCellEditorComponent(
        table: JTable, value: Any, isSelected: Boolean, row: Int, column: Int
    ): Component {
        this.editEntry = value as? DbRecord
        this.viewRow = row
        return getComp(table, value as? DbRecord, isSelected)
    }

    override fun getCellEditorValue(): Any {
        return 99
    }

    override fun isCellEditable(anEvent: EventObject?): Boolean {
        return true
    }

    override fun shouldSelectCell(anEvent: EventObject?): Boolean {
        return false
    }

    override fun stopCellEditing(): Boolean {
        viewRow = -1
        return true
    }

    override fun cancelCellEditing() {
        viewRow = -1
    }

    override fun addCellEditorListener(l: CellEditorListener) {
        // Noop
    }

    override fun removeCellEditorListener(l: CellEditorListener) {
        // Noop
    }

    private fun categoryFor(name: String): DownloadCategory? =
        AppContext.config.categories.firstOrNull { it.matches(name) }

    private fun getIcon(name: String): Icon {
        val cat = categoryFor(name) ?: return defaultIcon
        iconBadge.background = Color(30, 144, 255)
        return iconCache.getOrPut("${cat.id}:${cat.icon}") {
            createIcon(CategoryStyle.iconName(cat), 16, Color.WHITE)
        }
    }
}
