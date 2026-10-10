package xdm.app.ui.components

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.ui.screens.publishText
import xdm.app.utils.RemixIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.createIcon
import xdm.app.utils.px
import xdm.app.utils.scaledSize
import xdm.app.utils.showMenu
import xdm.core.downloaders.DownloadType
import xdm.core.util.FormatHelper.formatDateShort
import xdm.core.util.FormatHelper.formatSize
import xdm.core.util.FormatHelper.toLongEta
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import java.awt.*
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
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
    private val lblProgress: StatusLabel
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

    /** Hue of the tile currently shown, so hovering can deepen the same color. */
    private var tileHue: Color = AppTheme.tileHue(null)

    /**
     * Tile icons, cached per glyph and color. The key carries the glyph name so editing a
     * category's icon in settings invalidates the entry instead of showing the old glyph.
     */
    private val iconCache = HashMap<String, Icon>()

    /** Icon for a file that matches no category, and for the neutral pre-render state. */
    private val defaultIcon: Icon get() = tileIcon(CategoryStyle.ALL_ICON, AppTheme.tile(null as DownloadCategory?).glyph)

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

        panel = JPanel(BorderLayout(8.px, 5.px)).apply {
            background = AppTheme.listBackground
        }
        val p4 = JPanel(FlowLayout(FlowLayout.CENTER, 5.px, 5.px))
        p4.isOpaque = false
        val p3 = JPanel(BorderLayout())
        p3.background = AppTheme.tile(null as DownloadCategory?).background
        iconBadge = p3

        icoUnchecked = createIcon(RemixIcon.CHECKBOX_BLANK_LINE, 16, Color.WHITE)
        icoChecked = createIcon(RemixIcon.CHECKBOX_LINE, 16, Color.WHITE)
//        icoFile = createIcon(RemixIcon.FILE_ZIP_FILL, 16, Color.WHITE)
        icon = JLabel(defaultIcon)
        icon.border = ScaledEmptyBorder(9, 9, 9, 9)
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
                    icon.putClientProperty("old.icon", icon.icon)
                    icon.putClientProperty("old.background", iconBadge.background)
                    if (table.isRowSelected(viewRow) || table.selectedRowCount > 0) {
                        // Selection mode: the tile is already accent with white check boxes.
                        icon.icon = if (table.isRowSelected(viewRow)) icoChecked else icoUnchecked
                    } else {
                        // Outside selection mode, deepen the tile's own tint and offer the check box in its color.
                        val tile = AppTheme.tile(tileHue, hovered = true)
                        iconBadge.background = tile.background
                        icon.icon = tileIcon(RemixIcon.CHECKBOX_BLANK_LINE, tile.glyph)
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    val isSelectionMode = table.selectedRowCount > 0
                    icon.icon = if (table.isRowSelected(viewRow))
                        icoChecked
                    else
                        if (isSelectionMode) icoUnchecked else {
                            (icon.getClientProperty("old.background") as? Color)?.let { iconBadge.background = it }
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
        // A full circle; FlatLaf scales `arc` and clamps it to the panel size.
        p3.putClientProperty(FlatClientProperties.STYLE, "arc: 999")

        // icon.setOpaque(true);
        // icon.setBackground(Color.ORANGE);
        p3.add(icon)

        //      var chk = new JCheckBox();
        //      chk.setBorder(new EmptyBorder(0, 0, 0, 8));
        //      p4.add(chk);
        p4.add(p3)

        // p3.setBorder(new EmptyBorder(5,5,5,5));
        // The two text lines as one block, centered vertically against the icon tile. A 2-row
        // GridLayout split the height into equal cells and pinned the lines to the middle edge,
        // which drifts off-center as the row grows. The zero minimum width lets the title
        // ellipsize instead of making GridBagLayout fall back to minimum sizes.
        val lines = object : JPanel() {
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }
        lines.layout = BoxLayout(lines, BoxLayout.Y_AXIS)
        lines.isOpaque = false
        val content = JPanel(GridBagLayout())
        content.isOpaque = false

        lblTitle = JLabel("Some long title name for testing")
        lblTitle.foreground = AppTheme.listTitle
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
        val fnt = lblTitle.font.deriveFont(12.0f.px)
        lblInfo = JLabel("Some long title name for testing")
        lblInfo.verticalAlignment = SwingConstants.TOP
        lblInfo.font = fnt
        lblInfo.font = lblTitle.font.deriveFont(11.0f.px)
        lblInfo.foreground = Color.GRAY
        lblTitle.alignmentX = 0f
        lblInfo.alignmentX = 0f
        lines.add(lblTitle)
        lines.add(Box.createVerticalStrut(3.px))
        lines.add(lblInfo)
        content.add(lines, GridBagConstraints().apply {
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })

        val p1 = JPanel(BorderLayout(0, 0)).apply {
            background = AppTheme.listBackground
        }
        panDetails = JPanel(BorderLayout()).apply {
            background = AppTheme.listBackground
        }
        panDetails.border = ScaledEmptyBorder(0, 0, 0, 10)
        prg = JProgressBar()
        prg.preferredSize = scaledSize(60, 10)
        prg.alignmentY = Component.TOP_ALIGNMENT
        lblProgress = StatusLabel(lblTitle.font.deriveFont(11.0f.px))
        // prg.setPreferredSize(new Dimension(lblProgress.getPreferredSize().width + 5, 10));
        panDetails.add(lblProgress)
        panDetails.add(prg, BorderLayout.SOUTH)
        p1.add(panDetails)
        prg.border = ScaledEmptyBorder(0, 0, 5, 0)

        val buttonContainer = Box.createHorizontalBox()
        buttonContainer.border = ScaledEmptyBorder(10, 0, 10, 0)

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

        pauseGap = Box.createRigidArea(scaledSize(5, 10))
        resumeGap = Box.createRigidArea(scaledSize(5, 10))
        openFolderGap = Box.createRigidArea(scaledSize(5, 10))

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
        panel.border = ScaledEmptyBorder(0, 5, 5, 5)

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
        // In selection mode the tile turns accent so the white check boxes stay visible.
        if (isSelected || table.selectedRowCount > 0) {
            iconBadge.background = AppTheme.accent
            icon.icon = if (isSelected) icoChecked else icoUnchecked
        } else {
            icon.icon = getIcon(ent)
        }
        lblProgress.pill = when (ent.status) {
            RecordStatus.PAUSED -> StatusPill.PAUSED
            RecordStatus.ERROR -> StatusPill.FAILED
            else -> null
        }
        buttonContainer.isVisible = table.selectedRowCount == 0
        btnOpenFolder.isVisible = ent.status == RecordStatus.FINISHED
        openFolderGap.isVisible = btnOpenFolder.isVisible
        // A finalizing publish cannot be undone, so it offers neither pause nor resume.
        btnPause.isVisible = ent.status == RecordStatus.DOWNLOADING || ent.status == RecordStatus.READY
                || ent.status == RecordStatus.ASSEMBLING
                || (ent.status == RecordStatus.PUBLISHING && ent.movePhase != MovePhase.FINALIZING)
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
            } else if (ent.status.isStopped) {
                val label = text(if (ent.status == RecordStatus.ERROR) "STAT_FAILED" else "STAT_PAUSED")
                prgText = if (ent.progress > 0) {
                    String.format("%s %d%s", label, ent.progress, "%")
                } else {
                    label
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
                prgText = publishText(ent.movePhase, ent.progress)
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

    /**
     * A batch shows a folder on an accent tile, with a warning when some of its files failed;
     * anything else its category's glyph on that category's tinted tile. Sets the tile color too.
     */
    private fun getIcon(ent: DbRecord): Icon {
        if (ent.downloadType == DownloadType.Batch) {
            tileHue = AppTheme.accent
            val tile = AppTheme.tile(tileHue)
            iconBadge.background = tile.background
            val glyph = if (ent.failedFiles > 0) RemixIcon.FOLDER_WARNING_FILL else RemixIcon.FOLDER_FILL
            return tileIcon(glyph, tile.glyph)
        }
        val cat = categoryFor(ent.fileName)
        tileHue = AppTheme.tileHue(cat)
        val tile = AppTheme.tile(tileHue)
        iconBadge.background = tile.background
        return tileIcon(cat?.let { CategoryStyle.iconName(it) } ?: CategoryStyle.ALL_ICON, tile.glyph)
    }

    private fun tileIcon(glyph: RemixIcon, color: Color): Icon =
        iconCache.getOrPut("${glyph.name}:${color.rgb}") { createIcon(glyph, 16, color) }

    private enum class StatusPill { PAUSED, FAILED }

    /**
     * The status text right of the file name. With a [pill] it is drawn as a rounded pill (amber
     * when paused, red when failed), otherwise as plain text.
     */
    private class StatusLabel(private val plainFont: Font) : JLabel() {
        private val plainForeground = foreground

        init {
            font = plainFont
        }

        var pill: StatusPill? = null
            set(value) {
                if (field == value) return
                field = value
                foreground = when (value) {
                    StatusPill.PAUSED -> AppTheme.pausedForeground
                    StatusPill.FAILED -> AppTheme.failedForeground
                    null -> plainForeground
                }
                horizontalAlignment = if (value != null) CENTER else LEADING
                border = if (value != null) ScaledEmptyBorder(0, 10, 0, 10) else null
            }

        override fun paintComponent(g: Graphics) {
            val pill = pill
            if (pill != null) {
                val h = minOf(height, 24.px)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (pill == StatusPill.FAILED) AppTheme.failedBackground else AppTheme.pausedBackground
                g2.fillRoundRect(0, (height - h) / 2, width, h, h, h)
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
