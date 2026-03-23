package xdm.app.ui.components

import javax.swing.Icon

enum class SortKey {
    NAME,
    SIZE,
    TYPE,
    DATE,
}

sealed interface FilterItem {
    val text: String
    val icon: Icon
    val selectedIcon: Icon

    data class Category(
        val category: FilterCategory,
        override val text: String,
        override val icon: Icon,
        override val selectedIcon: Icon
    ) :
        FilterItem

    data class State(
        val state: FilterState,
        override val text: String,
        override val icon: Icon,
        override val selectedIcon: Icon
    ) :
        FilterItem
}

enum class FilterState() {
    All, Incomplete, Completed
}

enum class FilterCategory(val text: String) {
    All("CAT_ALL"), Docs("CAT_DOCUMENTS"),
    Zip("CAT_COMPRESSED"), Music("CAT_MUSIC"),
    Video("CAT_VIDEOS"), Apps("CAT_PROGRAMS")
}

val docExt = listOf(".pdf", ".docx", ".doc", ".ppt", ".pptx", ".odt", ".odf")
val zipExt = listOf(".zip", ".rar", ".7z", ".gz", ".tar", ".tgz", ".tz", ".bz2")
val musicExt = listOf(".mp3", ".aac", ".wav", ".ac3")
val videoExt = listOf(".ts", ".mp4", ".mkv", ".webm", ".avi")
val appsExt = listOf(".exe", ".msi", ".msix", ".deb", ".dmg", ".rpm", ".iso", ".pkg", ".sh", ".py")

fun isDoc(name: String) = docExt.any {
    name.endsWith(it, ignoreCase = true)
}

fun isZip(name: String) = zipExt.any {
    name.endsWith(it, ignoreCase = true)
}

fun isMusic(name: String) = musicExt.any {
    name.endsWith(it, ignoreCase = true)
}

fun isVideo(name: String) = videoExt.any {
    name.endsWith(it, ignoreCase = true)
}

fun isApp(name: String) = appsExt.any {
    name.endsWith(it, ignoreCase = true)
}