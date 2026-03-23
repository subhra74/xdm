package xdm.app.ui.components

import java.awt.*
import javax.swing.JComponent
import javax.swing.UIManager

class CircularProgress : JComponent() {
    override fun paint(g: Graphics) {
        val padding = 4
        val sweepAngle = ((_value.toFloat() * 360) / 100).toInt()
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = UIManager.getColor("ProgressBar.background")
        g2.stroke = stroke
        g2.drawArc(padding, padding, getWidth() - 2 * padding, getHeight() - 2 * padding, 90, -360)
        if (_value > 0) {
            g2.color = UIManager.getColor("ProgressBar.foreground")
            g2.drawArc(
                padding, padding, getWidth() - 2 * padding, getHeight() - 2 * padding, 90, -sweepAngle
            )
        }
        val fm = g2.fontMetrics
        val str = "$_value%"
        val w = fm.getStringBounds(str, g2).width.toInt()
        val lm = fm.getLineMetrics(str, g2)
        val h = (lm.ascent + lm.descent).toInt()
        g2.color = UIManager.getColor("Label.foreground")
        g2.drawString(str, ((getWidth() - w) / 2).toFloat(), ((getHeight() + h) / 2) - lm.descent)
    }

    val stroke: Stroke = BasicStroke(
        4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER
    )
    private var _value = 0

    var value: Int
        get() = _value
        set(value) {
            _value = value
            repaint()
        }
}
