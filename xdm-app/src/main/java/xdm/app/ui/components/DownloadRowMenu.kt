package xdm.app.ui.components

import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.core.downloaders.DownloadType
import java.awt.Component
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * The download menu shown by a row's ⋯ button and, in the Classic list, by a right click. Built
 * fresh for every show, from the record(s) it acts on.
 *
 * [withPrimaryActions] adds Open, Open folder, Pause/Resume and Delete to the single-row menu.
 * Classic sets it, since a right click is far from the row buttons; Modern keeps its ⋯ menu as is.
 */
class DownloadRowMenu(private val withPrimaryActions: Boolean) {

    /** The menu for one [entry], for its row's ⋯ button; [invoker] is any component of that row. */
    fun forRow(entry: DbRecord, invoker: Component): JPopupMenu = singleMenu(entry, invoker)

    /** The menu for [entries] at ([x], [y]) in [invoker]: the single-row menu for one, the bulk menu for more. */
    fun showAt(entries: List<DbRecord>, invoker: Component, x: Int, y: Int) {
        val menu = when (entries.size) {
            0 -> return
            1 -> singleMenu(entries[0], invoker)
            else -> bulkMenu(entries, invoker)
        }
        menu.show(invoker, x, y)
    }

    private fun singleMenu(ent: DbRecord, invoker: Component): JPopupMenu {
        val menu = JPopupMenu()
        val window = { SwingUtilities.getWindowAncestor(invoker) }
        if (withPrimaryActions) {
            menu.item("CTX_OPEN_FILE") { AppMenuHandler.openFile(ent, window()) }
            if (ent.status == RecordStatus.FINISHED) {
                menu.item("CTX_OPEN_FOLDER") { AppMenuHandler.openFolder(ent, window()) }
            }
            if (ent.canPause) menu.item("MENU_PAUSE") { AppMenuHandler.pauseDownload(ent) }
            if (ent.canResume) menu.item("MENU_RESUME") { AppMenuHandler.resumeDownload(ent) }
            menu.addSeparator()
        }
        // A batch has many links and no single one to refresh.
        if (ent.status.isStopped && !AppContext.refreshLinkInProgress.get() && ent.downloadType != DownloadType.Batch) {
            menu.item("MENU_REFRESH_LINK") { AppContext.app.showRefreshWindow(ent.id) }
        }
        if (ent.status.isStopped) {
            menu.item("MENU_SCHEDULE") { AppContext.app.showSchedulerWindow(ent.id) }
        }
        if (ent.status == RecordStatus.DOWNLOADING) {
            menu.item("LBL_SHOW_PROGRESS") { AppContext.app.showProgressWindow(ent.id, ent.fileName) }
        }
        if (ent.downloadType == DownloadType.Batch && ent.status == RecordStatus.FINISHED && ent.failedFiles > 0) {
            menu.item("CTX_RETRY_FAILED") { AppContext.downloader.retryFailedFiles(ent.id) }
        }
        // Change folder: stopped single-file downloads only, never while one is being moved.
        if (ent.status.isStopped && ent.downloadType != DownloadType.Batch && !AppContext.downloader.isMoving(ent.id)) {
            menu.item("CTX_CHANGE_FOLDER") { AppMenuHandler.changeFolder(ent, window()) }
        }
        if (ent.canRestart) {
            menu.item("MENU_RESTART") { AppMenuHandler.restartDownload(ent) }
        }
        menu.item("CTX_COPY_URL") { AppMenuHandler.copyUrl(ent) }
        menu.item("MENU_PROPERTIES") { AppContext.app.showPropertiesWindow(ent) }
        if (withPrimaryActions) {
            menu.addSeparator()
            menu.item("TOOL_DELETE") { AppMenuHandler.deleteDownload(ent, window()) }
        }
        return menu
    }

    /** Only the actions that make sense for many downloads, each shown when it applies to at least one. */
    private fun bulkMenu(entries: List<DbRecord>, invoker: Component): JPopupMenu {
        val menu = JPopupMenu()
        if (entries.any { it.canResume }) {
            menu.item("MENU_RESUME") { entries.filter { it.canResume }.forEach { AppMenuHandler.resumeDownload(it) } }
        }
        if (entries.any { it.canPause }) {
            menu.item("MENU_PAUSE") { entries.filter { it.canPause }.forEach { AppMenuHandler.pauseDownload(it) } }
        }
        if (entries.any { it.canRestart }) {
            menu.item("MENU_RESTART") { entries.filter { it.canRestart }.forEach { AppMenuHandler.restartDownload(it) } }
        }
        if (menu.componentCount > 0) menu.addSeparator()
        menu.item("TOOL_DELETE") {
            AppMenuHandler.deleteSelectedDownloads(entries, SwingUtilities.getWindowAncestor(invoker))
        }
        return menu
    }

    private val DbRecord.canRestart: Boolean
        get() = (status.isStopped || status == RecordStatus.FINISHED) && downloadType != DownloadType.Batch

    private fun JPopupMenu.item(id: String, action: () -> Unit) {
        add(JMenuItem(text(id)).apply {
            name = id
            addActionListener { action() }
        })
    }
}
