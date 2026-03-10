package xdm.app.utils

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.io.IOException
import javax.swing.JPopupMenu
import kotlin.math.max

object UiHelper {
    fun createSVGIcon(name: String, size: Int, color: Color?): FlatSVGIcon {
        val filter = ColorFilter()
        filter.add(Color.BLACK, color)
        try {
            val icon = FlatSVGIcon("icons/$name", size, size)
            icon.setColorFilter(filter)
            return icon
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    fun createSVGIcon(name: String, size: Int): FlatSVGIcon {
        try {
            val icon = FlatSVGIcon(UiHelper::class.java.getResourceAsStream("/icons/$name"))
            return icon.derive(size, size)
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    fun sameWidth(c1: Component, c2: Component) {
        val p1 = c1.preferredSize
        val p2 = c2.preferredSize
        val maxW = max(p1.width.toDouble(), p2.width.toDouble()).toInt()
        val maxH = max(p1.height.toDouble(), p2.height.toDouble()).toInt()
        val dim = Dimension(maxW, maxH)
        c1.preferredSize = dim
        c2.preferredSize = dim
    }

    fun showMenu(target: Component, menu: JPopupMenu) {
        menu.pack()
        val menuWidth = menu.preferredSize.width
        val targetWidth = target.preferredSize.width
        val x = targetWidth - menuWidth
        menu.invoker = target
        menu.show(target, x, target.height)
    }
}
