package xdm.app.ui.components

import javax.swing.Icon

data class FilterListItem(val itemType: FilterItemType, val text: String, val icon: Icon, val selectedIcon: Icon)

enum class FilterItemType {
    ALL,
    UNFINISHED,
    FINISHED,
    CATEGORY_UNFINISHED,
    CATEGORY_FINISHED,
    CAT_ALL,
    CAT_ALL_TYPES,
    CAT_DOCUMENTS,
    CAT_COMPRESSED,
    CAT_MUSIC,
    CAT_VIDEOS,
    CAT_PROGRAMS,
    ALL_QUEUE,
    QUEUE_ITEM
}