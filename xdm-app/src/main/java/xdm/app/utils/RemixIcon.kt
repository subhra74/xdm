package xdm.app.utils

import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Glyphs from the bundled Remix Icon font (`/fonts/remixicon.ttf`, v4.9.1).
 * Codepoints come from that release's `remixicon.css`; re-check them if the font is upgraded.
 */
enum class RemixIcon(val codepoint: Int) {
    ADD_LARGE_FILL(0xf4b0),
    ADD_LINE(0xea13),
    ARCHIVE_2_FILL(0xf3a5),
    ARCHIVE_2_LINE(0xf3a6),
    ARROW_DOWN_CIRCLE_FILL(0xea49),
    ARROW_DOWN_CIRCLE_LINE(0xea4a),
    ARROW_UP_DOWN_FILL(0xea73),
    BRUSH_LINE(0xeb01),
    CHECKBOX_BLANK_LINE(0xeb7f),
    CHECKBOX_CIRCLE_FILL(0xeb80),
    CHECKBOX_CIRCLE_LINE(0xeb81),
    CHECKBOX_LINE(0xeb85),
    CHROME_FILL(0xeb8c),
    CLOSE_CIRCLE_LINE(0xeb97),
    DELETE_BIN_LINE(0xec2a),
    DOWNLOAD_2_LINE(0xec54),
    EDGE_NEW_FILL(0xf31c),
    EDIT_LINE(0xec86),
    FILE_COPY_LINE(0xecd5),
    FILE_FILL(0xece0),
    FILE_LINE(0xeceb),
    FILE_LIST_2_FILL(0xecec),
    FILE_LIST_2_LINE(0xeced),
    FILE_SHIELD_FILL(0xed0a),
    FILE_SHIELD_LINE(0xed0b),
    FILE_TEXT_FILL(0xed0e),
    FILE_TEXT_LINE(0xed0f),
    FILE_ZIP_FILL(0xed1e),
    FILE_ZIP_LINE(0xed1f),
    FIREFOX_FILL(0xed34),
    FOLDER_3_LINE(0xed54),
    FOLDER_6_LINE(0xf43b),
    FOLDER_FILL(0xed61),
    FOLDER_LINE(0xed6a),
    GLOBAL_FILL(0xedce),
    GLOBAL_LINE(0xedcf),
    MENU_LINE(0xef3e),
    MICROSOFT_FILL(0xef57),
    MICROSOFT_LINE(0xef58),
    MOON_FILL(0xef72),
    MORE_2_FILL(0xef76),
    MOVIE_FILL(0xef80),
    MOVIE_LINE(0xef81),
    MV_FILL(0xef86),
    MV_LINE(0xef87),
    NOTIFICATION_3_LINE(0xef94),
    PAUSE_CIRCLE_LINE(0xefd6),
    PLAY_CIRCLE_LINE(0xf009),
    PROGRESS_2_FILL(0xf47c),
    PROGRESS_2_LINE(0xf47d),
    PUZZLE_LINE(0xf451),
    SEARCH_LINE(0xf0d1),
    SERVER_LINE(0xf0e0),
    SETTINGS_4_LINE(0xf0e8),
    SHIELD_KEYHOLE_LINE(0xf107),
    SORT_DESC(0xf160),
    SPARKLING_2_FILL(0xf36a),
    SPARKLING_2_LINE(0xf36b),
    SPEED_LINE(0xf177),
    SUN_FILL(0xf1bc),
    TERMINAL_BOX_LINE(0xf1f6),
    TOGGLE_FILL(0xf218),
    TOGGLE_LINE(0xf219),
    WIFI_LINE(0xf2c0),
    WINDOW_2_LINE(0xf2c4),
}

private object RemixFont {
    val base: Font by lazy {
        RemixFont::class.java.getResourceAsStream("/fonts/remixicon.ttf").use {
            Font.createFont(Font.TRUETYPE_FONT, it ?: error("remixicon.ttf missing from resources"))
        }
    }
}

/**
 * Paints a [RemixIcon] glyph centred in a [size]×[size] box. The glyph is tinted with [color], or with the
 * component's foreground when [color] is null; disabled components use `Label.disabledForeground`.
 */
class FontIcon(icon: RemixIcon, private val size: Int, private val color: Color? = null) : Icon {
    private val font = RemixFont.base.deriveFont(size.toFloat())
    private val glyph = String(Character.toChars(icon.codepoint))

    // Remix glyphs are drawn on a 24-unit em box, so centring on the advance/em keeps them aligned
    // like the old 24x24 SVGs. Computed once with an identity transform (independent of HiDPI scale).
    private val offsetX: Float
    private val baseline: Float

    init {
        val frc = FontRenderContext(AffineTransform(), true, true)
        val advance = font.getStringBounds(glyph, frc).width.toFloat()
        val lm = font.getLineMetrics(glyph, frc)
        offsetX = (size - advance) / 2f
        baseline = (size - (lm.ascent + lm.descent)) / 2f + lm.ascent
    }

    override fun getIconWidth() = size

    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g2.color = when {
                c != null && !c.isEnabled -> UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                color != null -> color
                else -> c?.foreground ?: UIManager.getColor("Label.foreground") ?: Color.GRAY
            }
            g2.font = font
            g2.drawString(glyph, x + offsetX, y + baseline)
        } finally {
            g2.dispose()
        }
    }
}

fun createIcon(icon: RemixIcon, size: Int, color: Color? = null): Icon = FontIcon(icon, size, color)
