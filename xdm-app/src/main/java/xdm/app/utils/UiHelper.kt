package xdm.app.utils

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.FlatSVGIcon.ColorFilter
import xdm.app.AppContext
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.Insets
import java.io.IOException
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.border.EmptyBorder
import kotlin.math.max

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
        val icon = FlatSVGIcon(AppContext::class.java.getResourceAsStream("/icons/$name"))
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

fun gbAdd(
    comp: JComponent,
    container: Container,
    gridX: Int = 0,
    gridY: Int = 0,
    alignment: Int = GridBagConstraints.WEST,
    padding: Insets = Insets(0, 0, 0, 0),
    colSpan: Int = 1,
    rowSpan: Int = 1,
    weightX: Double = 0.0,
    horizontalFill: Boolean = false,
    verticalFill: Boolean = false,
) {
    val gc = GridBagConstraints().apply {
        anchor = alignment
        insets = padding
        gridx = gridX
        gridy = gridY
        gridheight = rowSpan
        gridwidth = colSpan
        weightx = weightX
        fill =
            if (horizontalFill) GridBagConstraints.HORIZONTAL else if (verticalFill) GridBagConstraints.VERTICAL else GridBagConstraints.NONE
    }
    container.add(comp, gc)
}

fun fixHeight(comp: JComponent) {
    val height = comp.preferredSize.height
    comp.maximumSize = Dimension(comp.maximumSize.width, height)
    comp.minimumSize = Dimension(comp.minimumSize.width, height)
}

fun padding(comp: JComponent, padding: Int, bottomPadding: Boolean = true, topPadding: Boolean = false) {
    comp.border = EmptyBorder(
        if (topPadding) padding else 0,
        0,
        if (bottomPadding) padding else 0,
        0,
    )
}
