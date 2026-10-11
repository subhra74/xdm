package xdm.app.ui.components

import xdm.app.utils.px
import java.awt.Dimension
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import kotlin.math.min

/**
 * A combo box whose preferred and minimum width don't follow its longest item, so a long folder path or
 * track name can't widen the window packed around it. Lay it out with a horizontal fill: it still takes the
 * width the row gives it. The popup is sized from the items, not from these, so it still opens wide enough
 * to show a long item in full.
 *
 * `prototypeDisplayValue` would cap the width too, but it also narrows the popup to the prototype.
 */
class CappedComboBox<E>(
    model: ComboBoxModel<E> = DefaultComboBoxModel(),
    /** At 100%; scaled on every call, so a UI scale change applies. */
    private val maxWidth: Int = 200,
) : JComboBox<E>(model) {
    override fun getPreferredSize(): Dimension = capped(super.getPreferredSize())

    override fun getMinimumSize(): Dimension = capped(super.getMinimumSize())

    private fun capped(size: Dimension) = Dimension(min(size.width, maxWidth.px), size.height)
}
