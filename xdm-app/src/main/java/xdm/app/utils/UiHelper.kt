package xdm.app.utils

import xdm.app.AppContext
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.border.EmptyBorder
import kotlin.math.max

private val logoSizes = intArrayOf(16, 24, 32, 48, 64, 128, 256, 512)

private fun loadImage(path: String): BufferedImage =
    AppContext::class.java.getResourceAsStream(path).use {
        ImageIO.read(it ?: throw IOException("Missing resource $path"))
    }

/** Scales [src] to [w]x[h] with bicubic filtering (steps down by halves to avoid aliasing). */
fun scaleImage(src: BufferedImage, w: Int, h: Int): BufferedImage {
    var img = src
    do {
        val nw = max(w, img.width / 2)
        val nh = max(h, img.height / 2)
        val step = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
        val g = step.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, nw, nh, null)
        g.dispose()
        img = step
    } while (img.width != w || img.height != h)
    return img
}

/** The app logo at exactly [size] px, scaled from the nearest larger bundled PNG. */
fun logoImage(size: Int): BufferedImage {
    val best = logoSizes.firstOrNull { it >= size } ?: logoSizes.last()
    val img = loadImage("/images/xdm-logo-$best.png")
    return if (best == size) img else scaleImage(img, size, size)
}

/** A [size]px logo icon that stays sharp on HiDPI screens (1x, 1.5x, 2x variants). */
fun logoIcon(size: Int): ImageIcon {
    val variants = listOf(1.0, 1.5, 2.0).map { logoImage((size * it).toInt()) }
    return ImageIcon(BaseMultiResolutionImage(*variants.toTypedArray()))
}

/** The monochrome macOS tray glyph (black on transparent) at [size] px. */
fun trayMacImage(size: Int): BufferedImage = scaleImage(loadImage("/images/xdm-tray-mac-88.png"), size, size)

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
