package xdm.app.utils

import xdm.app.AppContext
import xdm.app.OS
import xdm.app.ui.screens.AppWindow
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.GridBagConstraints
import java.awt.Image
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPopupMenu
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

/**
 * The bundled logo keeps macOS-style padding (the artwork fills ~80% of the canvas), which looks
 * small among Windows icons, drawn edge to edge. This is the 512 px logo cropped to a square around
 * its artwork; Windows icons are scaled from it.
 */
private val croppedLogo: BufferedImage by lazy {
    val src = logoImage(512)
    var minX = src.width
    var minY = src.height
    var maxX = -1
    var maxY = -1
    for (y in 0 until src.height) {
        for (x in 0 until src.width) {
            if ((src.getRGB(x, y) ushr 24) > 16) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return@lazy src
    val side = max(maxX - minX, maxY - minY) + 1
    val left = ((minX + maxX + 1 - side) / 2).coerceIn(0, src.width - side)
    val top = ((minY + maxY + 1 - side) / 2).coerceIn(0, src.height - side)
    BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB).also { out ->
        out.createGraphics().apply { drawImage(src.getSubimage(left, top, side, side), 0, 0, null); dispose() }
    }
}

/**
 * The logo at [size] px for Windows: edge to edge, like other Windows icons. The 16 px PNG is
 * drawn by hand and already fills its canvas, so it is used as is.
 */
fun windowsLogoImage(size: Int): BufferedImage =
    if (size <= 16) logoImage(16) else scaleImage(croppedLogo, size, size)

/**
 * Icons for a top-level window's title bar and taskbar button. On Windows, edge-to-edge versions at
 * the sizes it asks for (16 title bar, 24/32 taskbar, 48 Alt+Tab, plus their 125-200% scales);
 * elsewhere the logo as is.
 */
val windowIconImages: List<Image> by lazy {
    if (detectOS() == OS.Windows) listOf(16, 20, 24, 32, 40, 48, 64, 256).map { windowsLogoImage(it) }
    else listOf(logoImage(256))
}

/** A [size]px logo icon that stays sharp on HiDPI screens (1x, 1.5x, 2x variants). */
fun logoIcon(size: Int): ImageIcon {
    val variants = listOf(1.0, 1.5, 2.0).map { logoImage((size * it).toInt()) }
    return ImageIcon(BaseMultiResolutionImage(*variants.toTypedArray()))
}

/** The monochrome macOS tray glyph (black on transparent) at [size] px. */
fun trayMacImage(size: Int): BufferedImage = scaleImage(loadImage("/images/xdm-tray-mac-88.png"), size, size)

/** Gives every component the preferred size of the widest and tallest one among them. */
fun sameWidth(vararg components: Component) {
    if (components.isEmpty()) return
    val maxW = components.maxOf { it.preferredSize.width }
    val maxH = components.maxOf { it.preferredSize.height }
    val dim = Dimension(maxW, maxH)
    components.forEach { it.preferredSize = dim }
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
    comp.border = ScaledEmptyBorder(
        if (topPadding) padding else 0,
        0,
        if (bottomPadding) padding else 0,
        0,
    )
}

/**
 * A dialog's height: [preferred] (pixels at 100%), but never taller than the main window, so it
 * opens no bigger than the app did, nor than the screen's usable area.
 */
fun dialogHeight(preferred: Int): Int {
    val main = Frame.getFrames().firstOrNull { it is AppWindow && it.isDisplayable }
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height
    return minOf(preferred.px, main?.height?.takeIf { it > 0 } ?: Int.MAX_VALUE, screen)
}
