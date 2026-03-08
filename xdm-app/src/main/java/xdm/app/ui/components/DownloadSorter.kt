package xdm.app.ui.components

import xdm.app.AppContext.config
import xdm.app.constants.SortKey
import xdm.app.data.DbRecord

class DownloadSorter : Comparator<DbRecord> {
    override fun compare(o1: DbRecord, o2: DbRecord): Int {
        val sortKey = config.sortKey
        val ascending = config.isSortAscending
        var res = 0
        when (sortKey) {
            SortKey.NAME -> res = o1.fileName.compareTo(o2.fileName)
            SortKey.SIZE -> res = o1.size.compareTo(o2.size)
            SortKey.DATE -> res = o1.date.compareTo(o2.date)
            SortKey.TYPE -> res = o1.status.compareTo(o2.status)
            else -> {
                // No ops
            }
        }
        return if (ascending) -res else res
    }
}
