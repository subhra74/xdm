package xdm.app.ui.components

import xdm.app.DownloadCategory
import java.awt.Color
import javax.swing.UIManager

/**
 * Main-window colors. The values live in the theme overrides under `resources/xdm/app/themes/`
 * (one file per FlatLaf theme), so the light and dark palettes are data rather than branches on
 * `config.theme`. The fallbacks only matter if those files fail to load.
 */
object AppTheme {
    private fun color(key: String, fallback: String): Color =
        UIManager.getColor(key) ?: UIManager.getColor(fallback) ?: Color.GRAY

    /** Background of the download list and everything around it (toolbar, split pane). */
    val listBackground: Color get() = color("XDM.list.background", "Table.background")
    /** File name in a download row: a step softer than the normal text. */
    val listTitle: Color get() = color("XDM.list.titleForeground", "Label.foreground")
    val emptyIconColor: Color get() = color("XDM.list.emptyIconColor", "Panel.background")

    val sidebarBackground: Color get() = color("XDM.sidebar.background", "Panel.background")
    val sidebarBorder: Color get() = color("XDM.sidebar.borderColor", "Component.borderColor")
    val sidebarForeground: Color get() = color("XDM.sidebar.foreground", "Label.foreground")
    val sidebarSelectedForeground: Color get() = color("XDM.sidebar.selectedForeground", "Label.foreground")
    val sidebarSelection: Color get() = color("XDM.sidebar.selectionBackground", "Button.background")
    val sidebarHover: Color get() = color("XDM.sidebar.hoverBackground", "Button.background")
    val sidebarIcon: Color get() = color("XDM.sidebar.iconColor", "Label.disabledForeground")
    val sidebarSection: Color get() = color("XDM.sidebar.sectionForeground", "Label.disabledForeground")
    val sidebarCount: Color get() = color("XDM.sidebar.countForeground", "Label.disabledForeground")
    val sidebarSelectedCount: Color get() = color("XDM.sidebar.selectedCountForeground", "Label.foreground")

    val accent: Color get() = color("XDM.accent", "Component.accentColor")

    val pausedForeground: Color get() = color("XDM.list.pausedForeground", "Label.foreground")
    val pausedBackground: Color get() = color("XDM.list.pausedBackground", "Button.background")
    val failedForeground: Color get() = color("XDM.list.failedForeground", "Actions.Red")
    val failedBackground: Color get() = color("XDM.list.failedBackground", "Button.background")

    /** Classic list: the selected and hovered row's card, across the whole row. */
    val listSelectionBackground: Color get() = color("XDM.list.selectionBackground", "Table.selectionInactiveBackground")
    val listHoverBackground: Color get() = color("XDM.list.hoverBackground", "XDM.sidebar.hoverBackground")

    /** Colors of a download row's round icon tile: [background] fill and [glyph] color. */
    data class Tile(val background: Color, val glyph: Color)

    /**
     * Tile for a download of [category] (null: a file no category claims). The fill is the
     * category color blended over the list background rather than a translucent color, so the
     * opaque badge panel paints cleanly.
     */
    fun tile(category: DownloadCategory?): Tile = tile(tileHue(category))

    fun tileHue(category: DownloadCategory?): Color = category?.let { categoryColor(it) } ?: otherColor

    /** [hovered] doubles the tint, for the tile under the mouse. */
    fun tile(hue: Color, hovered: Boolean = false): Tile {
        val tint = (UIManager.getInt("XDM.list.tileTint").takeIf { it > 0 } ?: 22) * (if (hovered) 2 else 1)
        val shade = UIManager.getInt("XDM.list.tileGlyphShade")
        return Tile(blend(listBackground, hue, tint / 100f), blend(hue, Color.BLACK, shade / 100f))
    }

    private val otherColor: Color get() = color("XDM.category.other", "Component.accentColor")

    private fun blend(c: Color, towards: Color, amount: Float): Color = Color(
        (c.red + (towards.red - c.red) * amount).toInt(),
        (c.green + (towards.green - c.green) * amount).toInt(),
        (c.blue + (towards.blue - c.blue) * amount).toInt()
    )

    private const val CUSTOM_HUES = 4

    /**
     * Color of a category (sidebar dot, Category combo dot, row tile): the one the user picked, else
     * the theme's: built-ins have their own hue, a user category takes one of the custom hues picked
     * from its id, so it keeps the same color across restarts and edits.
     */
    fun categoryColor(category: DownloadCategory): Color =
        category.color?.let { Color(it) }
            ?: UIManager.getColor("XDM.category.${category.id}")
            ?: UIManager.getColor("XDM.category.custom${Math.floorMod(category.id.hashCode(), CUSTOM_HUES) + 1}")
            ?: sidebarIcon
}
