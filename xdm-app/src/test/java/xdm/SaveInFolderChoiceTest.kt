package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    @BeforeEach
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

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun populatingDoesNotOverwriteTheStoredChoice() {
        config.autoSelectFolder = true
        populateSaveInFolders(model, combo)
        assertTrue(config.autoSelectFolder, "filling the combo must not look like a user choice")
    }

    @Test
    fun choosingAFolder_isRememberedImmediately() {
        config.autoSelectFolder = true
        populateSaveInFolders(model, combo)

        combo.selectedIndex = 1 // the default download folder, not "Automatic"

        assertFalse(config.autoSelectFolder, "changing the combo must persist without pressing Download")
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
