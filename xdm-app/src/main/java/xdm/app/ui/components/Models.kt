package xdm.app.ui.components

import xdm.app.DownloadCategory
import xdm.core.util.StableId
import javax.swing.Icon

enum class SortKey(override val id: Int) : StableId {
    NAME(1),
    SIZE(2),
    TYPE(3),
    DATE(4),
}

sealed interface FilterItem {
    val text: String
    val icon: Icon
    val selectedIcon: Icon

    /** A category row in the sidebar; a null [category] is the "All types" row. */
    data class Category(
        val category: DownloadCategory?,
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
