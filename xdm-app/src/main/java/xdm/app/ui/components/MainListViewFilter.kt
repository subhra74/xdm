package xdm.app.ui.components

import xdm.app.DownloadCategory
import xdm.app.RecordStatus
import javax.swing.RowFilter

/** [filterCategory] is null for "All types". */
class MainListViewFilter(
    var filterState: FilterState,
    var filterCategory: DownloadCategory?,
    var searchText: String
) :
    RowFilter<MainListViewModel, Int>() {
    override fun include(entry: Entry<out MainListViewModel, out Int>): Boolean {
        val index = entry.identifier
        val model = entry.model as MainListViewModel
        if (index < 0 || index >= model.rowCount) return false
        val rec = model.getItemAt(index) ?: return false
        if (searchText.isNotBlank() && !rec.fileName.contains(searchText, ignoreCase = true)) {
            return false
        }
        return when (filterState) {
            FilterState.Incomplete -> {
                if (rec.status == RecordStatus.FINISHED) {
                    return false
                }
                matchCategory(rec.fileName)
            }

            FilterState.Completed -> {
                if (rec.status != RecordStatus.FINISHED) {
                    return false
                }
                matchCategory(rec.fileName)
            }

            else -> matchCategory(rec.fileName)
        }
    }

    private fun matchCategory(name: String): Boolean =
        filterCategory?.matches(name) ?: true
}
