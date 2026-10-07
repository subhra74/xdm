package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.DbRecord
import xdm.app.I8N.text
import xdm.app.RecordStatus
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.px
import xdm.app.utils.scaledSize
import xdm.core.util.Logger
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Window
import java.util.concurrent.TimeUnit
import javax.swing.*

/** How old a download must be to be cleared, measured from when it was added. */
enum class ClearAge(val labelKey: String, val days: Long) {
    ANY("CLR_AGE_ANY", 0),
    WEEK("CLR_AGE_WEEK", 7),
    MONTH("CLR_AGE_MONTH", 30),
    THREE_MONTHS("CLR_AGE_3_MONTHS", 91),
    SIX_MONTHS("CLR_AGE_6_MONTHS", 182),
    YEAR("CLR_AGE_YEAR", 365),
}

/**
 * Which idle downloads Clear removes. Paused covers failed downloads too once XDM restarts, as
 * failures are only kept apart for the running session.
 */
data class ClearCriteria(val statuses: Set<RecordStatus>, val age: ClearAge) {
    fun matches(rec: DbRecord, now: Long): Boolean =
        rec.status in statuses && (age.days == 0L || rec.date <= now - TimeUnit.DAYS.toMillis(age.days))
}

/**
 * Asked for by the toolbar's Clear button: removes idle downloads picked by status and age, and
 * optionally their completed files. Downloads in progress or queued are never touched.
 */
class ClearDownloadsDialog(owner: Window?) : JDialog(owner, text("CLR_TITLE"), ModalityType.APPLICATION_MODAL) {
    private val chkFinished = JCheckBox(text("CLR_FINISHED"), true)
    private val chkPaused = JCheckBox(text("CLR_PAUSED"), true)
    private val chkFailed = JCheckBox(text("CLR_FAILED"), true)
    private val cmbAge = JComboBox(ClearAge.entries.map { text(it.labelKey) }.toTypedArray())
    private val chkDeleteFiles = JCheckBox(text("CLR_DELETE_FILES"))
    private val lblCount = JLabel(" ")
    private val btnClear = JButton(text("TOOL_CLEAR"))
    private val btnCancel = JButton(text("ND_CANCEL"))

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = ScaledEmptyBorder(15, 20, 10, 20)
        }
        fun row(vararg items: Component) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            items.forEachIndexed { i, c ->
                if (i > 0) add(Box.createRigidArea(scaledSize(15, 0)))
                add(c)
            }
        }
        fun gap(h: Int) = Box.createRigidArea(Dimension(0, h.px))

        content.add(row(JLabel(text("CLR_STATUS"))))
        content.add(gap(6))
        content.add(row(chkFinished, chkPaused, chkFailed))
        content.add(gap(14))
        content.add(row(JLabel(text("CLR_AGE")), cmbAge))
        content.add(gap(14))
        content.add(row(chkDeleteFiles))
        content.add(gap(14))
        content.add(row(lblCount))

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 10.px, 10.px)).apply {
            background = UIManager.getColor("Table.background")
            add(btnCancel)
            add(btnClear)
        }
        contentPane.add(content, BorderLayout.CENTER)
        contentPane.add(buttons, BorderLayout.SOUTH)
        getRootPane().defaultButton = btnClear

        listOf(chkFinished, chkPaused, chkFailed).forEach { it.addActionListener { updateCount() } }
        cmbAge.addActionListener { updateCount() }
        btnCancel.addActionListener { dispose() }
        btnClear.addActionListener { clear() }

        updateCount()
        pack()
        minimumSize = Dimension(420.px, height)
        setLocationRelativeTo(owner)
    }

    private fun criteria() = ClearCriteria(
        statuses = buildSet {
            if (chkFinished.isSelected) add(RecordStatus.FINISHED)
            if (chkPaused.isSelected) add(RecordStatus.PAUSED)
            if (chkFailed.isSelected) add(RecordStatus.ERROR)
        },
        age = ClearAge.entries[cmbAge.selectedIndex],
    )

    private fun updateCount() {
        val c = criteria()
        val now = System.currentTimeMillis()
        val n = AppContext.db.count { c.matches(it, now) }
        lblCount.text = text("CLR_COUNT").replace("%d", n.toString())
        btnClear.isEnabled = n > 0
    }

    private fun clear() {
        val c = criteria()
        val fromDisk = chkDeleteFiles.isSelected
        val now = System.currentTimeMillis()
        if (fromDisk && c.statuses.contains(RecordStatus.FINISHED)) {
            val files = AppContext.db.count { it.status == RecordStatus.FINISHED && c.matches(it, now) }
            if (files > 0 && JOptionPane.showConfirmDialog(
                    this, text("CLR_CONFIRM_FILES").replace("%d", files.toString()), text("CLR_TITLE"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE
                ) != JOptionPane.OK_OPTION
            ) return
        }
        // Purging reads and deletes several files per download: off the EDT, so a long list does
        // not freeze the window. The list view is refreshed by the manager when it is done.
        listOf(chkFinished, chkPaused, chkFailed, cmbAge, chkDeleteFiles, btnClear, btnCancel)
            .forEach { it.isEnabled = false }
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        Thread {
            try {
                val removed = AppContext.downloader.clearInactive(fromDisk) { c.matches(it, now) }
                Logger.info("XDM", "Cleared $removed downloads")
            } catch (e: Exception) {
                Logger.error("XDM", "Clear failed", e)
            }
            SwingUtilities.invokeLater { dispose() }
        }.apply { name = "clear-downloads"; isDaemon = true }.start()
    }
}
