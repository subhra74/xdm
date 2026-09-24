package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xdm.app.AppConfig
import xdm.app.AppContext
import xdm.app.I8N
import xdm.app.utils.persistFolderChoiceOnChange
import xdm.app.utils.populateSaveInFolders
import java.io.File
import java.nio.file.Files
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

/**
 * The "Save in" choice is remembered as soon as it is changed, not only when Download is pressed,
 * so closing the dialog or starting a download elsewhere still uses the folder last picked.
 */
class SaveInFolderChoiceTest {
    private lateinit var dir: File
    private lateinit var config: AppConfig
    private lateinit var model: DefaultComboBoxModel<String>
    private lateinit var combo: JComboBox<String>

    private fun autoLabel(): String = I8N.text("ND_AUTO_CAT") ?: "As per file type"

    @Before
    fun setup() {
        I8N.loadTexts("en")
        dir = Files.createTempDirectory("xdm-savein").toFile()
        config = AppConfig(dir.absolutePath).apply {
            defaultDownloadFolder = File(dir, "downloads").absolutePath
            savedFolders = listOf(File(dir, "other").absolutePath)
        }
        AppContext.config = config
        model = DefaultComboBoxModel()
        combo = JComboBox(model)
        persistFolderChoiceOnChange(combo)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun populatingDoesNotOverwriteTheStoredChoice() {
        config.autoSelectFolder = true
        populateSaveInFolders(model, combo)
        assertTrue("filling the combo must not look like a user choice", config.autoSelectFolder)
    }

    @Test
    fun choosingAFolder_isRememberedImmediately() {
        config.autoSelectFolder = true
        populateSaveInFolders(model, combo)

        combo.selectedIndex = 1 // the default download folder, not "Automatic"

        assertFalse("changing the combo must persist without pressing Download", config.autoSelectFolder)
        assertEquals(config.defaultDownloadFolder, config.recentFolders[config.folderIndex + 1])
    }

    @Test
    fun choosingAutomatic_isRememberedImmediately() {
        config.autoSelectFolder = false
        populateSaveInFolders(model, combo)

        combo.selectedIndex = 0 // "Automatic (by file type)"

        assertTrue(config.autoSelectFolder)
    }

    @Test
    fun theChoiceSurvivesReopeningTheDialog() {
        populateSaveInFolders(model, combo)
        combo.selectedIndex = 2 // the saved folder

        // Reopening rebuilds the combo from the config; the stored choice must come back selected.
        val reopened = JComboBox(DefaultComboBoxModel<String>())
        persistFolderChoiceOnChange(reopened)
        populateSaveInFolders(reopened.model as DefaultComboBoxModel<String>, reopened)

        assertEquals(combo.selectedItem, reopened.selectedItem)
        assertFalse(config.autoSelectFolder)
    }

    @Test
    fun firstEntryIsTheAutomaticOption() {
        populateSaveInFolders(model, combo)
        assertEquals(autoLabel(), model.getElementAt(0))
    }
}
