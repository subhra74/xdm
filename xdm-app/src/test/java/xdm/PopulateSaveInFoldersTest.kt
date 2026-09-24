package xdm

import org.junit.Assert.assertEquals
import org.junit.Test
import xdm.app.AppContext
import xdm.app.utils.populateSaveInFolders
import xdm.app.I8N
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

/**
 * Regression for CODE_REVIEW B21: `populateSaveInFolders` used `coerceIn(1, folders.size - 1)`,
 * which throws when `recentFolders` holds fewer than two entries — for instance when `distinct()`
 * collapses the "Automatic (by file type)" entry into the default folder.
 */
class PopulateSaveInFoldersTest : DownloadManagerTestBase() {

    private fun autoCategoryLabel(): String {
        I8N.loadTexts("en")
        return I8N.text("ND_AUTO_CAT")
    }

    private fun populate(): JComboBox<String> {
        val model = DefaultComboBoxModel<String>()
        val combo = JComboBox(model)
        populateSaveInFolders(model, combo)
        return combo
    }

    @Test
    fun singleFolderEntryDoesNotThrow() {
        // Collapses recentFolders to one entry.
        config.defaultDownloadFolder = autoCategoryLabel()
        config.autoSelectFolder = false
        config.folderIndex = 3

        assertEquals(1, AppContext.config.recentFolders.size)
        assertEquals(0, populate().selectedIndex)
    }

    @Test
    fun rememberedFolderIsSelected() {
        config.savedFolders = listOf("/tmp/one", "/tmp/two", "/tmp/three")
        config.autoSelectFolder = false
        config.folderIndex = 2

        val combo = populate()
        assertEquals(AppContext.config.recentFolders[3], combo.selectedItem)
    }

    @Test
    fun autoSelectPicksTheFileTypeEntry() {
        config.autoSelectFolder = true
        config.folderIndex = 2
        assertEquals(0, populate().selectedIndex)
    }

    @Test
    fun outOfRangeIndexIsClamped() {
        config.savedFolders = listOf("/tmp/one")
        config.autoSelectFolder = false
        config.folderIndex = 99

        val combo = populate()
        assertEquals(AppContext.config.recentFolders.size - 1, combo.selectedIndex)
    }
}
