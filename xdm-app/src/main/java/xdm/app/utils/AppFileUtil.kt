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
 * Returns the folder a file should land in when auto-categorization is on:
 * a per-type subfolder of [baseFolder], or [baseFolder] itself for unknown types.
 */
fun categoryFolderFor(fileName: String, baseFolder: String): String {
    val subFolder = when {
        isDoc(fileName) -> "Documents"
        isZip(fileName) -> "Compressed"
        isMusic(fileName) -> "Music"
        isVideo(fileName) -> "Video"
        isApp(fileName) -> "Programs"
        else -> return baseFolder
    }
    return File(baseFolder, subFolder).absolutePath
}

/**
 * Fills a "Save in" combo from [IAppConfig.recentFolders] (index 0 is the
 * "As per file type" entry) and selects the last remembered choice.
 */
fun populateSaveInFolders(model: DefaultComboBoxModel<String>, combo: JComboBox<String>) {
    val folders = config.recentFolders
    model.removeAllElements()
    model.addAll(folders)
    // coerceIn(1, size - 1) threw when the list held fewer than two entries, which happens if
    // `distinct()` collapses the "As per file type" entry into the default folder.
    val lastIndex = folders.size - 1
    combo.selectedIndex = when {
        lastIndex < 0 -> -1
        config.autoSelectFolder -> 0
        else -> (config.folderIndex + 1).coerceIn(minOf(1, lastIndex), lastIndex)
    }
}

/** True when the "As per file type" entry is selected in a combo filled by [populateSaveInFolders]. */
fun isAutoCategorySelected(combo: JComboBox<String>) = combo.selectedIndex == 0

/** The real folder behind the current combo selection (the default folder for "As per file type"). */
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
