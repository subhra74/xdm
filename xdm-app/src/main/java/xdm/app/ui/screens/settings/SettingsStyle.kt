package xdm.app.ui.screens.settings

import com.formdev.flatlaf.FlatClientProperties
import xdm.app.utils.RemixIcon
import xdm.app.utils.createIcon
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/**
 * Shared visual language for the settings panels: a small muted caption above each rounded
 * card, rows of "title + description on the left, control on the right" separated by
 * hairlines, pill toggles instead of check boxes, and icon tiles instead of combo boxes
 * wherever the choice is short enough to show all at once.
 */

internal fun settingsAccentColor(): Color =
    UIManager.getColor("Component.accentColor")
        ?: UIManager.getColor("Component.focusColor")
        ?: UIManager.getColor("Label.foreground")
        ?: Color(0x4F, 0x9E, 0xEE)

internal fun settingsMutedColor(): Color =
    UIManager.getColor("Label.disabledForeground")
        ?: (UIManager.getColor("Label.foreground") ?: Color.GRAY).let { Color(it.red, it.green, it.blue, 170) }

/** Base panel color the cards, tiles and hairlines are all derived from. */
private fun settingsBase(): Color = UIManager.getColor("Panel.background") ?: Color(0x3C, 0x3F, 0x41)

private fun isDark(c: Color): Boolean = (c.red * 299 + c.green * 587 + c.blue * 114) / 1000 < 128

private fun shift(c: Color, amount: Int): Color {
    fun clamp(v: Int) = v.coerceIn(0, 255)
    return Color(clamp(c.red + amount), clamp(c.green + amount), clamp(c.blue + amount))
}

/** Fill of a raised surface (card, tile) against the panel background. */
internal fun settingsSurface(hovered: Boolean = false): Color {
    val base = settingsBase()
    return if (isDark(base)) shift(base, if (hovered) 24 else 16) else shift(base, if (hovered) -14 else -8)
}

/** Border/hairline color against the panel background. */
internal fun settingsStroke(): Color {
    val base = settingsBase()
    return if (isDark(base)) shift(base, 34) else shift(base, -22)
}

/** The large bold heading shown at the top of a settings panel. */
internal fun settingsTitle(text: String): JLabel = JLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    font = font.deriveFont(Font.BOLD, 19.0f)
    border = EmptyBorder(0, 2, 18, 0)
}

/** The small muted caption that sits *outside* and above a card. */
internal fun settingsSectionLabel(text: String): JLabel = JLabel(text.uppercase()).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    font = font.deriveFont(Font.BOLD, 11.0f)
    foreground = settingsMutedColor()
    border = EmptyBorder(0, 4, 7, 0)
}

/**
 * A titled group: the caption above, then a rounded card holding [rows] with a hairline
 * between each pair. The caption deliberately lives outside the card so the page reads as
 * grouped rows rather than a stack of boxed headings.
 */
internal fun settingsSection(title: String, vararg rows: JComponent): JComponent {
    val box = Box.createVerticalBox().apply { alignmentX = Component.LEFT_ALIGNMENT }
    box.add(settingsSectionLabel(title))
    box.add(settingsCard(*rows))
    // A vertical Box takes the tallest child's maximum, which is unbounded for the card.
    box.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    return box
}

/** The rounded card on its own, without a caption. */
internal fun settingsCard(vararg rows: JComponent): JComponent {
    val panel = SettingsCard()
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = EmptyBorder(2, 18, 2, 18)
    rows.forEachIndexed { i, row ->
        row.alignmentX = Component.LEFT_ALIGNMENT
        if (i > 0) panel.add(Hairline())
        panel.add(row)
    }
    return panel
}

/**
 * One row of a card: a bold title with an optional muted description underneath, and the
 * control pushed to the right edge. [control] may be null for a row that is only text.
 */
internal fun settingsRow(title: String, subtitle: String? = null, control: JComponent? = null): JComponent =
    SettingsRow(labelStack(title, subtitle), control)

/** A row whose left side is a component of its own rather than a title/description pair. */
internal fun settingsCustomRow(left: JComponent, control: JComponent? = null): JComponent =
    SettingsRow(left, control)

/** A row that is one full-width component (a text area, a folder input, a tile group). */
internal fun settingsFullRow(title: String?, subtitle: String?, content: JComponent): JComponent =
    FullRow().apply {
        if (title != null) {
            add(labelStack(title, subtitle))
            add(Box.createRigidArea(Dimension(0, 9)))
        } else if (subtitle != null) {
            add(settingsLeftAligned(settingsHint(subtitle)))
            add(Box.createRigidArea(Dimension(0, 9)))
        }
        content.alignmentX = Component.LEFT_ALIGNMENT
        add(content)
    }

/**
 * Height is reported from the current preferred size rather than frozen at construction: a
 * card built before its content exists (the category list is filled by `load()`, after the
 * card is assembled) would otherwise stay at the height it had while empty.
 */
private class FullRow : JPanel() {
    init {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        border = EmptyBorder(13, 0, 13, 0)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

private fun labelStack(title: String, subtitle: String?): JComponent = JPanel().apply {
    isOpaque = false
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    alignmentX = Component.LEFT_ALIGNMENT
    add(JLabel(title).apply {
        alignmentX = Component.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN, 13.0f)
    })
    if (subtitle != null) {
        add(Box.createRigidArea(Dimension(0, 3)))
        add(JLabel(subtitle).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN, 11.0f)
            foreground = settingsMutedColor()
        })
    }
}

/** A muted footnote, for notes that belong to a whole card rather than one row. */
internal fun settingsHint(text: String): JLabel = JLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    font = font.deriveFont(Font.PLAIN, 11.0f)
    foreground = settingsMutedColor()
}

/** Wraps a component so it hugs the left edge inside a vertical BoxLayout. */
internal fun settingsLeftAligned(comp: JComponent): JComponent =
    Box.createHorizontalBox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(comp)
        add(Box.createHorizontalGlue())
    }

/** The gap between two sections. */
internal fun settingsGap(): Component = Box.createRigidArea(Dimension(0, 18))

/** A 1px divider, for lists that build their own rows rather than going through [settingsCard]. */
internal fun settingsHairline(): JComponent = Hairline()

/**
 * A single line of text that shrinks instead of stretching its container: it reports a
 * preferred width of zero and middle-ellipsizes whatever does not fit. Long file paths and
 * long extension lists are what force a horizontal scroll bar, so anything user-supplied and
 * unbounded in width goes through this.
 */
internal class EllipsisLabel(text: String = "", muted: Boolean = false, scale: Float = 0f) : JComponent() {
    private var value: String = text

    init {
        font = UIManager.getFont("Label.font").let { if (scale != 0f) it.deriveFont(it.size2D + scale) else it }
        foreground = if (muted) settingsMutedColor() else UIManager.getColor("Label.foreground")
    }

    fun setText(text: String) {
        value = text
        toolTipText = text
        repaint()
    }

    override fun getPreferredSize(): Dimension =
        Dimension(0, getFontMetrics(font).height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = font
        g2.color = foreground
        val fm = g2.fontMetrics
        g2.drawString(fit(value, fm, width), 0, fm.ascent)
        g2.dispose()
    }

    /** Drops characters from the middle until the string fits, keeping both ends readable. */
    private fun fit(text: String, fm: java.awt.FontMetrics, max: Int): String {
        if (max <= 0 || fm.stringWidth(text) <= max) return text
        val ellipsis = "…"
        var head = text.length / 2
        var tail = text.length - head
        while (head > 0 && tail > 0) {
            if (head >= tail) head-- else tail--
            val candidate = text.take(head) + ellipsis + text.takeLast(tail)
            if (fm.stringWidth(candidate) <= max) return candidate
        }
        return ellipsis
    }
}

/** Rounds a field's corners. Applied to every input in the settings tree. */
internal fun <T : JComponent> rounded(comp: T): T = comp.apply {
    putClientProperty(FlatClientProperties.STYLE, "arc: 12")
}

/**
 * A plain text button. Settings buttons carry no icon: the label is the whole control, and
 * a glyph next to it only adds noise.
 */
internal fun settingsButton(text: String, action: () -> Unit): JButton =
    JButton(text).apply {
        rounded(this)
        val h = preferredSize.height
        maximumSize = Dimension(maximumSize.width, h)
        minimumSize = Dimension(minimumSize.width, h)
        addActionListener { action() }
    }

/** A borderless icon-only button, for the per-row actions in the category list. */
internal fun settingsIconButton(icon: RemixIcon, tooltip: String?, action: () -> Unit): JButton =
    JButton(createIcon(icon, 17, settingsMutedColor())).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        toolTipText = tooltip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = Dimension(30, 30)
        preferredSize = size
        maximumSize = size
        minimumSize = size
        rolloverIcon = createIcon(icon, 17, settingsAccentColor())
        addActionListener { action() }
    }

/**
 * Base for the settings panels: a vertically stacked panel that stretches to the scroll
 * pane's width (so cards fill it) but scrolls vertically when it runs out of room.
 */
abstract class SettingsPanel : JPanel(), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 48
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = visibleRect.height
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** Panel that paints a rounded, theme-aware background so grouped settings read as a card. */
internal class SettingsCard : JPanel() {
    init {
        isOpaque = false
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun getAlignmentX(): Float = Component.LEFT_ALIGNMENT

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = settingsSurface()
        g2.fillRoundRect(0, 0, width - 1, height - 1, 18, 18)
        g2.color = settingsStroke()
        g2.drawRoundRect(0, 0, width - 1, height - 1, 18, 18)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** The 1px divider drawn between two rows of a card. */
internal class Hairline : JComponent() {
    init {
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension = Dimension(1, 1)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 1)

    override fun paintComponent(g: Graphics) {
        g.color = settingsStroke()
        g.fillRect(0, 0, width, 1)
    }
}

/** Label (or any component) on the left, control pinned right, with the row's own padding. */
private class SettingsRow(left: JComponent, control: JComponent?) : JPanel(BorderLayout(16, 0)) {
    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = EmptyBorder(11, 0, 11, 0)
        left.alignmentX = Component.LEFT_ALIGNMENT
        add(left, BorderLayout.CENTER)
        if (control != null) add(control, BorderLayout.EAST)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * The on/off control used throughout settings, drawn with the Remix `toggle-line` /
 * `toggle-fill` glyphs. Behaves like the JCheckBox it replaces, so action listeners that
 * enable dependent fields carry over unchanged.
 */
class SettingsToggle : JToggleButton() {
    init {
        isOpaque = false
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = false
        border = EmptyBorder(0, 0, 0, 0)
        margin = java.awt.Insets(0, 0, 0, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        icon = createIcon(RemixIcon.TOGGLE_LINE, 26, settingsMutedColor())
        rolloverIcon = createIcon(RemixIcon.TOGGLE_LINE, 26, settingsAccentColor())
        selectedIcon = createIcon(RemixIcon.TOGGLE_FILL, 26, settingsAccentColor())
        rolloverSelectedIcon = selectedIcon
        val size = Dimension(30, 28)
        preferredSize = size
        maximumSize = size
        minimumSize = size
    }
}

/** One choice in a [SettingsOptionGroup]. */
internal class SettingsOption<T>(val value: T, val label: String, val icon: RemixIcon)

/**
 * A row of rounded icon tiles standing in for a short combo box: every choice is visible at
 * once, with its glyph above its name. Used for theme, download-complete action and proxy
 * type — anything numeric stays a combo, where tiles would be absurd.
 *
 * Each tile is a real [JToggleButton] in a [ButtonGroup], so it is focusable, operable with
 * the keyboard and reported to assistive technology as a radio button. Only the painting is
 * custom.
 */
internal class SettingsOptionGroup<T>(private val options: List<SettingsOption<T>>) : JPanel() {
    private val tiles = mutableListOf<OptionTile>()
    private val group = ButtonGroup()

    /** Notified after the user picks a tile; a programmatic [selected] set does not fire it. */
    var onChange: (() -> Unit)? = null

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        layout = GridLayout(1, options.size, 10, 0)
        options.forEach { option ->
            val tile = OptionTile(option)
            tile.addActionListener { onChange?.invoke() }
            group.add(tile)
            tiles.add(tile)
            add(tile)
        }
        tiles.firstOrNull()?.isSelected = true
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    var selected: T
        get() = options[tiles.indexOfFirst { it.isSelected }.coerceAtLeast(0)].value
        set(value) {
            val index = options.indexOfFirst { it.value == value }
            tiles[if (index >= 0) index else 0].isSelected = true
            tiles.forEach { it.repaint() }
        }

    /** A single tile: glyph on top, name underneath, accent ring when chosen. */
    private class OptionTile(option: SettingsOption<*>) : JToggleButton(option.label) {
        private val glyph = option.icon

        init {
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            horizontalAlignment = SwingConstants.CENTER
            iconTextGap = 9
            border = EmptyBorder(14, 8, 12, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.PLAIN, 12.0f)
            icon = createIcon(glyph, 26, settingsMutedColor())
            selectedIcon = createIcon(glyph, 26, settingsAccentColor())
            rolloverIcon = icon
            rolloverSelectedIcon = selectedIcon
            addChangeListener { refresh() }
            refresh()
        }

        private fun refresh() {
            font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN)
            foreground = if (isSelected) UIManager.getColor("Label.foreground") else settingsMutedColor()
        }

        override fun paintComponent(g: Graphics) {
            val accent = settingsAccentColor()
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (isSelected) {
                Color(accent.red, accent.green, accent.blue, 38)
            } else {
                settingsSurface(model.isRollover)
            }
            g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
            g2.color = when {
                isSelected -> accent
                model.isRollover || isFocusOwner -> settingsStroke()
                else -> settingsSurface()
            }
            g2.drawRoundRect(0, 0, width - 1, height - 1, 14, 14)
            if (isSelected) g2.drawRoundRect(1, 1, width - 3, height - 3, 13, 13)
            g2.dispose()
            super.paintComponent(g)
        }
    }
}
