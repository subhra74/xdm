package xdm.app.utils

import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Brand glyphs from the bundled Font Awesome Free brands font (`/fonts/fa-brands-400.ttf`, 6.x).
 * Codepoints come from that release's `css/brands.css`; re-check them if the font is upgraded.
 */
enum class BrandIcon(val codepoint: Int) {
    BRAVE(0xe63c),
    CHROME(0xf268),
    EDGE(0xf282),
    FIREFOX_BROWSER(0xe007),
}

private object BrandFont {
    val base: Font by lazy {
        BrandFont::class.java.getResourceAsStream("/fonts/fa-brands-400.ttf").use {
            Font.createFont(Font.TRUETYPE_FONT, it ?: error("fa-brands-400.ttf missing from resources"))
        }
    }
}

/**
 * Paints a [BrandIcon] scaled to fit a [size]×[size] box. Font Awesome glyphs differ in width, so the
 * glyph outline is fitted by its visual bounds rather than by font size; that way every brand icon
 * comes out the same size. Tinted with [color], or the component's foreground when [color] is null.
 */
class BrandFontIcon(icon: BrandIcon, private val size: Int, private val color: Color? = null) : Icon {
    private val shape: Shape

    init {
        // Outline computed once at a large reference size, then fitted to the box.
        val frc = FontRenderContext(AffineTransform(), true, true)
        val outline = BrandFont.base.deriveFont(512f)
            .createGlyphVector(frc, String(Character.toChars(icon.codepoint)))
            .outline
        val b = outline.bounds2D
        val scale = size / maxOf(b.width, b.height)
        val tx = AffineTransform()
        tx.translate((size - b.width * scale) / 2, (size - b.height * scale) / 2)
        tx.scale(scale, scale)
        tx.translate(-b.x, -b.y)
        shape = tx.createTransformedShape(outline)
    }

    override fun getIconWidth() = size

    override fun getIconHeight() = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = when {
                c != null && !c.isEnabled -> UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                color != null -> color
                else -> c?.foreground ?: UIManager.getColor("Label.foreground") ?: Color.GRAY
            }
            g2.translate(x, y)
            g2.fill(shape)
        } finally {
            g2.dispose()
        }
    }
}
