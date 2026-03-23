package xdm.app.ui.components

import xdm.app.RecordStatus
import javax.swing.RowFilter

class MainListViewFilter(var filterState: FilterState, var filterCategory: FilterCategory, var searchText: String) :
    RowFilter<MainListViewModel, Int>() {
    override fun include(entry: Entry<out MainListViewModel, out Int>): Boolean {
        val index = entry.identifier
        val model = entry.model as MainListViewModel
        if (index < 0 || index >= model.rowCount) return false
        val rec = model.getItemAt(index)
        if (searchText.isNotBlank() && !rec.fileName.contains(searchText, ignoreCase = true)) {
            return false
        }
        return when (filterState) {
            FilterState.Incomplete -> {
                if (rec.status == RecordStatus.FINISHED) {
                    return false
                }
                if (filterCategory == FilterCategory.All) return true
                matchCategory(rec.fileName, filterCategory)
            }

            FilterState.Completed -> {
                if (rec.status != RecordStatus.FINISHED) {
                    return false
                }
                if (filterCategory == FilterCategory.All) return true
                matchCategory(rec.fileName, filterCategory)
            }

            else -> {
                if (filterCategory == FilterCategory.All) return true
                matchCategory(rec.fileName, filterCategory)
            }
        }
    }

    private fun matchCategory(name: String, category: FilterCategory): Boolean {
        return when (category) {
            FilterCategory.All -> true
            FilterCategory.Docs -> isDoc(name)
            FilterCategory.Zip -> isZip(name)
            FilterCategory.Music -> isMusic(name)
            FilterCategory.Video -> isVideo(name)
            FilterCategory.Apps -> isApp(name)
        }
    }
}