package xdm.app.ui.screens.settings

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JComboBox
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** A row description wraps onto more lines as its row narrows, instead of being cut off with "…". */
class SettingsDescriptionWrapTest {

    private val longText = "The language XDM's menus, dialogs and labels are shown in. Takes effect the next " +
            "time XDM starts, so the current window keeps its language until then."

    private fun layOut(c: Container, width: Int) {
        // The description's height is only known once a pass gave it a width, and the card's after that;
        // in the app the description's revalidate() drives these extra passes.
        repeat(3) {
            forEachContainer(c) { it.invalidate() } // drops the requirements BoxLayout caches
            c.setSize(width, c.preferredSize.height)
            // Not shown on screen, so there is no peer and validate() would skip the layout.
            forEachContainer(c) { it.doLayout() }
        }
    }

    private fun forEachContainer(c: Container, action: (Container) -> Unit) {
        action(c)
        c.components.filterIsInstance<Container>().forEach { forEachContainer(it, action) }
    }

    private fun description(c: Container): JTextArea =
        c.components.firstNotNullOfOrNull { comp ->
            comp as? JTextArea ?: (comp as? Container)?.let { runCatching { description(it) }.getOrNull() }
        } ?: error("no description in $c")

    @Test
    fun descriptionWrapsToTheRowWidth() {
        SwingUtilities.invokeAndWait {
            val combo = JComboBox(arrayOf("Default (English)"))
            val card = settingsCard(settingsRow("Language", longText, combo))

            layOut(card, 1200)
            val wide = description(card)
            val oneLine = wide.height
            assertTrue(wide.width > 0, "the description is laid out")

            layOut(card, 520)
            val narrow = description(card)
            assertTrue(narrow.height >= oneLine * 2, "narrow row wraps: ${narrow.height} vs one line $oneLine")
            val comboX = SwingUtilities.convertPoint(combo.parent, combo.x, 0, narrow.parent).x
            assertTrue(narrow.x + narrow.width <= comboX, "text stays left of the control")
            val bottom = SwingUtilities.convertPoint(narrow.parent, 0, narrow.y + narrow.height, card).y
            assertTrue(bottom <= card.height, "the card grows to fit every line")

            val img = BufferedImage(card.width, card.height, BufferedImage.TYPE_INT_ARGB)
            card.paint(img.graphics)
            System.getProperty("xdm.dumpRow")?.let { javax.imageio.ImageIO.write(img, "png", java.io.File(it)) }
        }
    }
}
