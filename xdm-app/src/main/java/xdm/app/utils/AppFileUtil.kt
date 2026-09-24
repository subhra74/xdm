package xdm.app.utils

import xdm.app.AppContext.config
import xdm.app.AppContext.taskInfoDB
import xdm.app.DbRecord
import xdm.app.ui.components.*
import xdm.core.downloaders.DownloadType
import java.io.File
import java.util.*
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox

fun getFileFolder(ent: DbRecord): Pair<String?, String?>? {
    val folder: String?
    val fileName: String?
    when (ent.downloadType) {
        DownloadType.Http -> {
            val md = taskInfoDB.getHttpTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Hls -> {
            val md = taskInfoDB.getHlsTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Dash -> {
            val md = taskInfoDB.getDashTask(ent.id) ?: return null
            folder =
                Optional.ofNullable(md.userSelectedDownloadFolder)
                    .orElse(md.defaultDownloadFolder)
            fileName = md.fileName
        }

        DownloadType.Torrent -> TODO()
    }

    return Pair(fileName, folder)
}

/**
 * Returns the folder a file should land in when auto-categorization is on: the folder of the
 * first matching category, or [baseFolder] when nothing matches. A category's folder stands
 * on its own, so the base folder only decides where uncategorized files go.
 */
fun categoryFolderFor(fileName: String, baseFolder: String): String {
    val category = config.categories.firstOrNull { it.matches(fileName) } ?: return baseFolder
    return category.folder.ifBlank { baseFolder }
}

/**
 * Fills a "Save in" combo from [IAppConfig.recentFolders] (index 0 is the
 * "Automatic (by file type)" entry) and selects the last remembered choice.
 */
fun populateSaveInFolders(model: DefaultComboBoxModel<String>, combo: JComboBox<String>) {
    // Filling the model moves the selection, which would otherwise look like a user choice to the
    // listener installed by [persistFolderChoiceOnChange] and overwrite the setting being restored.
    combo.putClientProperty(POPULATING, true)
    try {
        fillSaveInFolders(model, combo)
    } finally {
        combo.putClientProperty(POPULATING, false)
    }
}

private const val POPULATING = "xdm.saveIn.populating"

/**
 * Persists the "Save in" choice the moment the user changes it, rather than only when they press
 * Download. Closing the dialog, or starting a download from somewhere else entirely, then still
 * uses the folder they last picked.
 */
fun persistFolderChoiceOnChange(combo: JComboBox<String>) {
    combo.addActionListener {
        if (combo.getClientProperty(POPULATING) != true && combo.selectedIndex >= 0) {
            rememberFolderChoice(combo)
        }
    }
}

private fun fillSaveInFolders(model: DefaultComboBoxModel<String>, combo: JComboBox<String>) {
    val folders = config.recentFolders
    model.removeAllElements()
    model.addAll(folders)
    // coerceIn(1, size - 1) threw when the list held fewer than two entries, which happens if
    // `distinct()` collapses the "Automatic (by file type)" entry into the default folder.
    val lastIndex = folders.size - 1
    combo.selectedIndex = when {
        lastIndex < 0 -> -1
        config.autoSelectFolder -> 0
        else -> (config.folderIndex + 1).coerceIn(minOf(1, lastIndex), lastIndex)
    }
}

/**
 * The base folder behind the user's last "Save in" choice. Downloads started without a dialog —
 * from the browser extension, say — use this so they land where the user last chose rather than in
 * a hardcoded folder.
 */
fun rememberedBaseFolder(): String =
    if (config.autoSelectFolder) config.defaultDownloadFolder
    else config.recentFolders.getOrNull(config.folderIndex + 1) ?: config.defaultDownloadFolder

/** True when the user's last "Save in" choice was "Automatic (by file type)". */
fun rememberedAutoCategorize(): Boolean = config.autoSelectFolder

/** True when the "Automatic (by file type)" entry is selected in a combo filled by [populateSaveInFolders]. */
fun isAutoCategorySelected(combo: JComboBox<String>) = combo.selectedIndex == 0

/** The real folder behind the current combo selection (the default folder for "Automatic (by file type)"). */
fun selectedBaseFolder(combo: JComboBox<String>): String =
    if (isAutoCategorySelected(combo)) config.defaultDownloadFolder
    else combo.selectedItem?.toString() ?: config.defaultDownloadFolder

private const val MAX_SAVED_FOLDERS = 5

/** Persists the combo selection so the next dialog opens with the same choice. */
fun rememberFolderChoice(combo: JComboBox<String>) {
    if (isAutoCategorySelected(combo)) {
        config.autoSelectFolder = true
    } else {
        val folder = selectedBaseFolder(combo)
        config.autoSelectFolder = false
        if (folder != config.defaultDownloadFolder) {
            config.savedFolders = (listOf(folder) + config.savedFolders.filter { it != folder })
                .take(MAX_SAVED_FOLDERS)
        }
        config.folderIndex = (config.recentFolders.indexOf(folder) - 1).coerceAtLeast(0)
    }
    config.save()
}
