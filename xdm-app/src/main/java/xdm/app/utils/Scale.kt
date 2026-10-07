package xdm.app.utils

import com.formdev.flatlaf.util.UIScale
import java.awt.Component
import java.awt.Dimension
import java.awt.Insets
import javax.swing.border.EmptyBorder

/*
 * Pixel sizes written in the code are for 100%. Where Java2D does the scaling (Windows, macOS,
 * whole-number scales on Linux) FlatLaf's factor is 1 and these return the value unchanged; on a
 * fractional X11 scale (X11UiScale) Java2D stays at 1x and these apply the factor instead.
 */

/** This many pixels at 100%, scaled to the current UI scale. */
val Int.px: Int get() = UIScale.scale(this)

/** This font size or length at 100%, scaled to the current UI scale. */
val Float.px: Float get() = UIScale.scale(this)

/** A [Dimension] given in pixels at 100%. */
fun scaledSize(width: Int, height: Int) = Dimension(width.px, height.px)

/** [Insets] given in pixels at 100%. */
fun scaledInsets(top: Int, left: Int, bottom: Int, right: Int) = Insets(top.px, left.px, bottom.px, right.px)

/**
 * An empty border given in pixels at 100%, scaled when laid out. Not FlatLaf's `FlatEmptyBorder`:
 * that one is a `UIResource`, so installing a component's UI replaces it with the look-and-feel's
 * default border (a scroll pane got its outline back).
 */
class ScaledEmptyBorder(top: Int, left: Int, bottom: Int, right: Int) : EmptyBorder(top, left, bottom, right) {
    override fun getBorderInsets(c: Component?, insets: Insets): Insets {
        insets.set(top.px, left.px, bottom.px, right.px)
        return insets
    }

    override fun getBorderInsets(): Insets = scaledInsets(top, left, bottom, right)
}
