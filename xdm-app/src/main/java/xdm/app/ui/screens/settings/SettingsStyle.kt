package xdm.app.ui.screens.settings

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

/** Shared visual language for the settings panels: rounded, theme-aware "cards". */

internal fun settingsAccentColor(): Color =
    UIManager.getColor("Component.accentColor")
        ?: UIManager.getColor("Component.focusColor")
        ?: UIManager.getColor("Label.foreground")
        ?: Color(0x4F, 0x9E, 0xEE)

internal fun settingsMutedColor(): Color =
    UIManager.getColor("Label.disabledForeground")
        ?: (UIManager.getColor("Label.foreground") ?: Color.GRAY).let { Color(it.red, it.green, it.blue, 170) }

/** The large bold heading shown at the top of a settings panel. */
internal fun settingsTitle(text: String): JLabel = JLabel(text).apply {
    alignmentX = Component.LEFT_ALIGNMENT
    font = font.deriveFont(Font.BOLD, 18.0f)
    border = EmptyBorder(0, 2, 14, 0)
}

/** A rounded card section with a header title and a set of stacked rows. */
internal fun settingsCard(title: String, vararg rows: JComponent): JComponent {
    val panel = SettingsCard()
    panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
    panel.border = EmptyBorder(14, 16, 16, 16)
    panel.alignmentX = Component.LEFT_ALIGNMENT

    val header = JLabel(title).apply {
        font = font.deriveFont(Font.BOLD, 14.0f)
        alignmentX = Component.LEFT_ALIGNMENT
    }
    panel.add(header)
    panel.add(Box.createRigidArea(Dimension(0, 12)))

    rows.forEachIndexed { i, row ->
        row.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(row)
        if (i < rows.size - 1) panel.add(Box.createRigidArea(Dimension(0, 10)))
    }
    return panel
}

/** Wraps a component so it hugs the left edge inside a vertical BoxLayout. */
internal fun settingsLeftAligned(comp: JComponent): JComponent =
    Box.createHorizontalBox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(comp)
        add(Box.createHorizontalGlue())
    }

/** A label on the left and a control pushed to the right edge. */
internal fun settingsRow(label: JComponent, control: JComponent): JComponent =
    Box.createHorizontalBox().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        add(label)
        add(Box.createHorizontalGlue())
        add(control)
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
        val base = UIManager.getColor("Panel.background") ?: Color(0x3C, 0x3F, 0x41)
        val luminance = (base.red * 299 + base.green * 587 + base.blue * 114) / 1000
        val fill = if (luminance < 128) shift(base, 16) else shift(base, -8)
        val stroke = if (luminance < 128) shift(base, 34) else shift(base, -22)

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width - 1, height - 1, 18, 18)
        g2.color = stroke
        g2.drawRoundRect(0, 0, width - 1, height - 1, 18, 18)
        g2.dispose()
        super.paintComponent(g)
    }

    private fun shift(c: Color, amount: Int): Color {
        fun clamp(v: Int) = v.coerceIn(0, 255)
        return Color(clamp(c.red + amount), clamp(c.green + amount), clamp(c.blue + amount))
    }
}
