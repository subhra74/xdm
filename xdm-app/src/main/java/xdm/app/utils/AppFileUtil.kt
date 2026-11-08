package xdm.app.utils

import xdm.app.AppConfig
import xdm.app.AppContext.config
import xdm.app.AppContext.taskInfoDB
import xdm.app.IAppConfig
import xdm.app.resolvedNoneFolder
import xdm.core.downloaders.DownloadType
import xdm.manager.CategoryRules
import xdm.manager.DownloadCategory
import java.io.File

/** The category with [id], or null for None (including an id whose category was deleted). */
fun categoryById(id: String?): DownloadCategory? =
    id?.let { wanted -> config.categories.firstOrNull { it.id == wanted } }

/** The folder a download in [category] is saved to: the category's folder, or the None folder. */
fun folderFor(category: DownloadCategory?): String =
    category?.folder?.takeIf { it.isNotBlank() } ?: config.resolvedNoneFolder

/**
 * The category a browser capture starts in: the rule match, unless the user's last hand-pick was None
 * ([IAppConfig.rememberNone]), in which case rules preselect nothing.
 */
fun captureCategory(fileName: String?, url: String?, pageUrl: String?): DownloadCategory? =
    if (config.rememberNone) null else CategoryRules.match(fileName, url, pageUrl)

/**
 * Where a browser capture goes when it starts without a dialog: the category the rules pick (none if
 * None is remembered) and that category's folder, or the None folder. Returns `categoryId to targetFolder`.
 */
fun captureDestination(fileName: String?, url: String?, pageUrl: String?): Pair<String?, String> {
    val category = captureCategory(fileName, url, pageUrl)
    return category?.id to folderFor(category)
}

/** Records a hand-picked category (null = None): only None is remembered, and a category clears it. */
fun rememberCategoryPick(category: DownloadCategory?) {
    val none = category == null
    if (config.rememberNone == none) return
    config.rememberNone = none
    config.save()
}

/**
 * Records a hand-chosen folder: it moves to the top of the folder history, and when chosen under None it
 * also becomes the None folder. A folder chosen under a category is a one-off for that download.
 */
fun rememberHandPickedFolder(folder: String, underNone: Boolean) {
    val path = File(folder).absolutePath
    config.folderHistory = (listOf(path) + config.folderHistory.filter { File(it).absolutePath != path })
        .take(AppConfig.MAX_FOLDER_HISTORY)
    if (underNone) config.noneFolder = path
    config.save()
}
