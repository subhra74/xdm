package xdm.app.ui.components

import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.UIManager

/**
 * Ring + percentage label used by the progress window.
 *
 * The dashed, antialiased background ring never changes, so it is rendered once into [ringCache] and
 * blitted afterwards: redrawing it on every update was the bulk of the Marlin (Java2D) work this
 * component showed in profiles. The setter also ignores repeats, so a progress event that reports the
 * same whole percent costs no paint at all.
 */
class CircularProgress : JComponent() {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val padding = 4
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.drawImage(ring(), 0, 0, null)
        if (_value > 0) {
            val sweepAngle = ((_value.toFloat() * 360) / 100).toInt()
            g2.color = UIManager.getColor("ProgressBar.foreground")
            g2.stroke = stroke
            g2.drawArc(
                padding, padding, width - 2 * padding, height - 2 * padding, 90, -sweepAngle
            )
        }
        val fm = g2.fontMetrics
        val str = "$_value%"
        val w = fm.getStringBounds(str, g2).width.toInt()
        val lm = fm.getLineMetrics(str, g2)
        val h = (lm.ascent + lm.descent).toInt()
        g2.color = UIManager.getColor("Label.foreground")
        g2.drawString(str, ((width - w) / 2).toFloat(), ((height + h) / 2) - lm.descent)
    }

    /** The static background ring, re-rendered only when the size or the theme colour changes. */
    private fun ring(): BufferedImage {
        val colour = UIManager.getColor("ProgressBar.background")
        ringCache?.let { if (width == it.width && height == it.height && colour == ringColour) return it }
        val padding = 4
        val image = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = colour
            g2.stroke = stroke
            g2.drawArc(padding, padding, width - 2 * padding, height - 2 * padding, 90, -360)
        } finally {
            g2.dispose()
        }
        ringCache = image
        ringColour = colour
        return image
    }

    private var ringCache: BufferedImage? = null
    private var ringColour: Color? = null

    val stroke: Stroke = BasicStroke(
        4f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 1.0f, floatArrayOf(4.0f, 5.0f), 1.0f
    )
    private var _value = 0

    var value: Int
        get() = _value
        set(value) {
            if (value == _value) return
            _value = value
            repaint()
        }
}
