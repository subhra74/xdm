package xdm.app.ui.components

import xdm.app.DbRecord
import xdm.app.DownloadCategory
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.ui.screens.moveText
import xdm.app.utils.RemixIcon
import xdm.app.utils.categoryById
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
import javax.swing.*

typealias ActionCallBack = (DbRecord) -> Unit

/** A finalizing publish cannot be undone, so it offers neither pause nor resume. */
val DbRecord.canPause: Boolean
    get() = status == RecordStatus.DOWNLOADING || status == RecordStatus.READY || status == RecordStatus.ASSEMBLING
            || (status == RecordStatus.MOVING && movePhase != MovePhase.FINALIZING)

val DbRecord.canResume: Boolean
    get() = status != RecordStatus.FINISHED && status != RecordStatus.DOWNLOADING && status != RecordStatus.READY
            && status != RecordStatus.ASSEMBLING && status != RecordStatus.MOVING

/**
 * What a download row shows: the icon tile, file name and status lines, progress and the row
 * buttons. Display only; the list views (Modern and Classic) add their own mouse handling on top,
 * so neither mode's clicks can leak into the other. [root] lets a view paint its own row background.
 */
class DownloadRowPanel(val root: JPanel = JPanel()) {
    val lblTitle: JLabel
    val icon: JLabel
    val iconBadge: JPanel
    private val lblInfo: JLabel
    private val lblProgress: StatusLabel
    private val prg: JProgressBar
    private val gap = "  -  "
    private val btnOpenFolder: JButton
    private val btnPause: JButton
    private val btnResume: JButton
    private val btnDelete: JButton
    private val btnMenu: JButton
    private val pauseGap: Component
    private val resumeGap: Component
    private val openFolderGap: Component
    private val buttonContainer: Component

    /** The record the row buttons act on; null for a renderer, which has no live buttons. */
    var entry: DbRecord? = null

    var onPauseClick: ActionCallBack? = null
    var onResumeClick: ActionCallBack? = null
    var onOpenFolderClick: ActionCallBack? = null
    var onDeleteClick: ActionCallBack? = null
    var onMenuClick: ActionCallBack? = null

    /** Hue of the tile currently shown, so hovering can deepen the same color. */
    var tileHue: Color = AppTheme.tileHue(null)
        private set

    /**
     * Tile icons, cached per glyph and color. The key carries the glyph name so editing a
     * category's icon in settings invalidates the entry instead of showing the old glyph.
     */
    private val iconCache = HashMap<String, Icon>()

    /** Icon for a file that matches no category, and for the neutral pre-render state. */
    val defaultIcon: Icon get() = tileIcon(CategoryStyle.ALL_ICON, AppTheme.tile(null as DownloadCategory?).glyph)

    init {
        root.layout = BorderLayout(8.px, 5.px)
        root.background = AppTheme.listBackground
        val p4 = JPanel(FlowLayout(FlowLayout.CENTER, 5.px, 5.px))
        p4.isOpaque = false
        // A circle in the panel's background color. Painted here on a transparent panel: FlatLaf's
        // `arc` fills the corners with the parent's flat background, a square over a painted row.
        val p3 = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background
                g2.fillOval(0, 0, width, height)
                g2.dispose()
            }
        }
        p3.isOpaque = false
        p3.background = AppTheme.tile(null as DownloadCategory?).background
        iconBadge = p3

        icon = JLabel(defaultIcon)
        icon.border = ScaledEmptyBorder(9, 9, 9, 9)
        p3.add(icon)
        p4.add(p3)

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
        lblTitle.verticalAlignment = SwingConstants.BOTTOM
        lblInfo = JLabel("Some long title name for testing")
        lblInfo.verticalAlignment = SwingConstants.TOP
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

        val p1 = JPanel(BorderLayout(0, 0))
        // Transparent so a row background painted by [root] (Classic's selection wash) shows through.
        val panDetails = JPanel(BorderLayout()).apply { isOpaque = false }
        panDetails.border = ScaledEmptyBorder(0, 0, 0, 10)
        prg = JProgressBar()
        prg.preferredSize = scaledSize(60, 10)
        prg.alignmentY = Component.TOP_ALIGNMENT
        lblProgress = StatusLabel(lblTitle.font.deriveFont(11.0f.px))
        panDetails.add(lblProgress)
        panDetails.add(prg, BorderLayout.SOUTH)
        prg.border = ScaledEmptyBorder(0, 0, 5, 0)

        val buttonContainer = Box.createHorizontalBox()
        buttonContainer.border = ScaledEmptyBorder(10, 0, 10, 0)

        btnPause = createButton(RemixIcon.PAUSE_CIRCLE_LINE, Color.GRAY) {
            entry?.let {
                Logger.info(it)
                onPauseClick?.invoke(it)
            }
        }
        btnResume = createButton(RemixIcon.PLAY_CIRCLE_LINE, Color.GRAY) {
            entry?.let {
                Logger.info(it)
                onResumeClick?.invoke(it)
            }
        }
        btnDelete = createButton(RemixIcon.DELETE_BIN_LINE, Color.GRAY) { entry?.let { onDeleteClick?.invoke(it) } }
        btnMenu = createButton(RemixIcon.MORE_2_FILL) { entry?.let { onMenuClick?.invoke(it) } }
        btnOpenFolder = createButton(RemixIcon.FOLDER_6_LINE, Color.GRAY) { entry?.let { onOpenFolderClick?.invoke(it) } }

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

        p1.add(buttonContainer, BorderLayout.EAST)
        p1.add(panDetails)
        p1.isOpaque = false

        root.add(p4, BorderLayout.WEST)
        root.add(content)
        root.add(p1, BorderLayout.EAST)
        root.border = ScaledEmptyBorder(0, 5, 5, 5)

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
        get() = root.preferredSize.height
    /** Shows [menu] under the row's ⋯ button. */
    fun showMenuUnderButton(menu: JPopupMenu) {
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

    /** Shows [ent]'s texts, progress and buttons; the tile is left to the caller ([showFileIcon]). */
    fun update(ent: DbRecord, showButtons: Boolean) {
        lblProgress.pill = when (ent.status) {
            RecordStatus.PAUSED -> StatusPill.PAUSED
            RecordStatus.ERROR -> StatusPill.FAILED
            else -> null
        }
        buttonContainer.isVisible = showButtons
        btnOpenFolder.isVisible = ent.status == RecordStatus.FINISHED
        openFolderGap.isVisible = btnOpenFolder.isVisible
        btnPause.isVisible = ent.canPause
        pauseGap.isVisible = btnPause.isVisible
        btnResume.isVisible = ent.canResume
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
            } else if (ent.status == RecordStatus.MOVING) {
                prgText = moveText(ent.movePhase, ent.progress)
                prg.isVisible = true
            }
            lblProgress.text = prgText
        }
    }

    /**
     * A batch shows a folder on a tile in the None color, with a warning when some of its files failed.
     * Any other download shows the glyph of its file type (from the extension) on a tile tinted with the
     * color of the category it was saved with (neutral for None or a deleted category). Sets the tile
     * color too.
     */
    fun showFileIcon(ent: DbRecord) {
        if (ent.downloadType == DownloadType.Batch) {
            tileHue = AppTheme.tileHue(null)
            val tile = AppTheme.tile(tileHue)
            iconBadge.background = tile.background
            val glyph = if (ent.failedFiles > 0) RemixIcon.FOLDER_WARNING_FILL else RemixIcon.FOLDER_FILL
            icon.icon = tileIcon(glyph, tile.glyph)
            return
        }
        tileHue = AppTheme.tileHue(categoryById(ent.categoryId))
        val tile = AppTheme.tile(tileHue)
        iconBadge.background = tile.background
        icon.icon = tileIcon(CategoryStyle.glyphForFile(ent.fileName), tile.glyph)
    }

    fun tileIcon(glyph: RemixIcon, color: Color): Icon =
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
