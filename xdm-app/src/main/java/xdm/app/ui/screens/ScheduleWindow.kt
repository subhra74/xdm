package xdm.app.ui.screens

import xdm.app.AppContext
import xdm.app.I8N.text
import xdm.app.ScheduleEntry
import xdm.app.ScheduleType
import xdm.app.utils.gbAdd
import xdm.app.utils.sameWidth
import java.awt.*
import java.util.Calendar
import java.util.Date
import javax.swing.*
import javax.swing.border.EmptyBorder

class ScheduleWindow(parent: Window, private val downloadId: Long) :
    JDialog(parent, text("TITLE_SCHEDULER"), ModalityType.APPLICATION_MODAL) {

    // Schedule type radio buttons
    private val radioOneTime = JRadioButton(text("MSG_SHD_ONCE"))
    private val radioWeekly = JRadioButton(text("MSG_SHD_WEEKLY"))

    // Card panel to swap between sub-panels
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // ONE_TIME controls
    private val dateSpinner: JSpinner
    private val hourSpinnerOneTime: JSpinner
    private val minuteSpinnerOneTime: JSpinner
    private val chkStopOneTime = JCheckBox(text("MSG_SHD_ENABLE_STOP"))
    private val stopDateSpinner: JSpinner
    private val stopHourSpinnerOneTime: JSpinner
    private val stopMinuteSpinnerOneTime: JSpinner
    private val stopLabelsOneTime = mutableListOf<JComponent>()

    // WEEKLY controls
    private val hourSpinnerWeekly: JSpinner
    private val minuteSpinnerWeekly: JSpinner
    private val chkStopWeekly = JCheckBox(text("MSG_SHD_ENABLE_STOP"))
    private val stopHourSpinnerWeekly: JSpinner
    private val stopMinuteSpinnerWeekly: JSpinner
    private val stopLabelsWeekly = mutableListOf<JComponent>()

    // Day-of-week checkboxes and corresponding Calendar constants (Mon–Sun order)
    private val dayLabels = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val dayConstants = intArrayOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )
    private val dayCheckBoxes = Array(7) { i -> JCheckBox(dayLabels[i]) }

    init {
        // Default time: now + 1 hour, rounded to the next minute
        val defaultCal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        val defaultDate = defaultCal.time
        val defaultHour = defaultCal.get(Calendar.HOUR_OF_DAY)
        val defaultMinute = defaultCal.get(Calendar.MINUTE)

        // Default stop: one hour after the default start
        val defaultStopCal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        val defaultStopDate = defaultStopCal.time
        val defaultStopHour = defaultStopCal.get(Calendar.HOUR_OF_DAY)
        val defaultStopMinute = defaultStopCal.get(Calendar.MINUTE)

        dateSpinner = JSpinner(SpinnerDateModel(defaultDate, null, null, Calendar.DAY_OF_MONTH))
        hourSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultHour, 0, 23, 1))
        minuteSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultMinute, 0, 59, 1))
        stopDateSpinner = JSpinner(SpinnerDateModel(defaultStopDate, null, null, Calendar.DAY_OF_MONTH))
        stopHourSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultStopHour, 0, 23, 1))
        stopMinuteSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultStopMinute, 0, 59, 1))

        hourSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultHour, 0, 23, 1))
        minuteSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultMinute, 0, 59, 1))
        stopHourSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultStopHour, 0, 23, 1))
        stopMinuteSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultStopMinute, 0, 59, 1))

        initUI()
        populateExistingEntry()
        syncStopControls()

        defaultCloseOperation = DISPOSE_ON_CLOSE
        //isResizable = false
        size = Dimension(500, 460)
        setLocationRelativeTo(parent)
    }

    private fun initUI() {
        val mainPanel = JPanel(GridBagLayout())
        mainPanel.border = EmptyBorder(15, 20, 10, 20)

        // --- Download info row ---
        val record = AppContext.db.getById(downloadId)
        val infoText = record?.fileName ?: "---"
        val lblDownload = JLabel(text("MSG_SHD_DOWNLOAD"))
        val lblInfo = JLabel(infoText)

        gbAdd(
            lblDownload, mainPanel,
            gridX = 0, gridY = 0,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 0, 12, 8)
        )
        gbAdd(
            lblInfo, mainPanel,
            gridX = 1, gridY = 0, colSpan = 4, weightX = 1.0,
            padding = Insets(0, 0, 12, 0),
            horizontalFill = true
        )

        // --- Schedule type row ---
        val lblType = JLabel(text("MSG_SHD_TYPE"))
        val typeGroup = ButtonGroup()
        radioOneTime.isSelected = true
        typeGroup.add(radioOneTime)
        typeGroup.add(radioWeekly)

        val typePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        typePanel.add(radioOneTime)
        typePanel.add(Box.createRigidArea(Dimension(20, 0)))
        typePanel.add(radioWeekly)

        gbAdd(
            lblType, mainPanel,
            gridX = 0, gridY = 1,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 0, 10, 8)
        )
        gbAdd(
            typePanel, mainPanel,
            gridX = 1, gridY = 1, colSpan = 4,
            padding = Insets(0, 0, 10, 0),
            horizontalFill = true
        )

        // --- Card panel ---
        cardPanel.add(buildOneTimePanel(), CARD_ONE_TIME)
        cardPanel.add(buildWeeklyPanel(), CARD_WEEKLY)

        gbAdd(
            cardPanel, mainPanel,
            gridX = 0, gridY = 2, colSpan = 5,
            padding = Insets(0, 0, 5, 0),
            horizontalFill = true
        )

        // Vertical spacer: absorbs all leftover height so rows stay at the top
        val vSpacer = JPanel()
        vSpacer.isOpaque = false
        mainPanel.add(vSpacer, GridBagConstraints().apply {
            gridx = 0; gridy = 3; gridwidth = 5
            weighty = 1.0; fill = GridBagConstraints.VERTICAL
        })

        // Wire radio listeners to flip cards
        radioOneTime.addActionListener { cardLayout.show(cardPanel, CARD_ONE_TIME) }
        radioWeekly.addActionListener { cardLayout.show(cardPanel, CARD_WEEKLY) }

        // Stop-time checkboxes enable their own row of controls
        chkStopOneTime.addActionListener { syncStopControls() }
        chkStopWeekly.addActionListener { syncStopControls() }

        contentPane.add(mainPanel, BorderLayout.CENTER)

        // --- Button bar ---
        val btnBar = JPanel()
        btnBar.background = UIManager.getColor("Table.background")
        btnBar.border = EmptyBorder(10, 15, 10, 15)
        btnBar.layout = BoxLayout(btnBar, BoxLayout.X_AXIS)

        val btnCancel = JButton(text("ND_CANCEL"))
        btnCancel.addActionListener { dispose() }

        val btnSchedule = JButton(text("MSG_SHD_SCHEDULE"))
        btnSchedule.addActionListener { onSchedule() }

        sameWidth(btnCancel, btnSchedule)
        getRootPane().defaultButton = btnSchedule

        btnBar.add(Box.createHorizontalGlue())
        btnBar.add(btnCancel)
        btnBar.add(Box.createRigidArea(Dimension(10, 0)))
        btnBar.add(btnSchedule)

        contentPane.add(btnBar, BorderLayout.SOUTH)
    }

    private fun buildOneTimePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(text("MSG_SHD_DT"))

        // Use a clean date-only format for the date spinners
        dateSpinner.editor = JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd")
        stopDateSpinner.editor = JSpinner.DateEditor(stopDateSpinner, "yyyy-MM-dd")
        styleTimeSpinner(hourSpinnerOneTime)
        styleTimeSpinner(minuteSpinnerOneTime)
        styleTimeSpinner(stopHourSpinnerOneTime)
        styleTimeSpinner(stopMinuteSpinnerOneTime)

        val lblDate = JLabel(text("MSG_SHD_DATE"))
        val lblTime = JLabel(text("MSG_SHD_TIME"))
        val lblColon = JLabel(":")
        val lblStopDate = JLabel(text("MSG_SHD_STOP_DATE"))
        val lblStopTime = JLabel(text("MSG_SHD_STOP_TIME"))
        val lblStopColon = JLabel(":")
        stopLabelsOneTime.addAll(listOf(lblStopDate, lblStopTime, lblStopColon))

        gbAdd(
            lblDate, panel,
            gridX = 0, gridY = 0,
            alignment = GridBagConstraints.EAST,
            padding = Insets(8, 10, 8, 8)
        )
        gbAdd(
            dateSpinner, panel,
            gridX = 1, gridY = 0, colSpan = 3, weightX = 1.0,
            padding = Insets(8, 0, 8, 10),
            horizontalFill = true
        )

        gbAdd(
            lblTime, panel,
            gridX = 0, gridY = 1,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 10, 12, 8)
        )
        gbAdd(hourSpinnerOneTime, panel, gridX = 1, gridY = 1, padding = Insets(0, 0, 12, 4))
        gbAdd(lblColon, panel, gridX = 2, gridY = 1, padding = Insets(0, 0, 12, 4))
        gbAdd(minuteSpinnerOneTime, panel, gridX = 3, gridY = 1, padding = Insets(0, 0, 12, 10))

        gbAdd(
            chkStopOneTime, panel,
            gridX = 0, gridY = 2, colSpan = 4, weightX = 1.0,
            padding = Insets(0, 8, 8, 10),
            horizontalFill = true
        )

        gbAdd(
            lblStopDate, panel,
            gridX = 0, gridY = 3,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 10, 8, 8)
        )
        gbAdd(
            stopDateSpinner, panel,
            gridX = 1, gridY = 3, colSpan = 3, weightX = 1.0,
            padding = Insets(0, 0, 8, 10),
            horizontalFill = true
        )

        gbAdd(
            lblStopTime, panel,
            gridX = 0, gridY = 4,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 10, 12, 8)
        )
        gbAdd(stopHourSpinnerOneTime, panel, gridX = 1, gridY = 4, padding = Insets(0, 0, 12, 4))
        gbAdd(lblStopColon, panel, gridX = 2, gridY = 4, padding = Insets(0, 0, 12, 4))
        gbAdd(stopMinuteSpinnerOneTime, panel, gridX = 3, gridY = 4, padding = Insets(0, 0, 12, 10))

        return panel
    }

    private fun buildWeeklyPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(text("MSG_SHD_REPEAT_ON"))

        styleTimeSpinner(hourSpinnerWeekly)
        styleTimeSpinner(minuteSpinnerWeekly)
        styleTimeSpinner(stopHourSpinnerWeekly)
        styleTimeSpinner(stopMinuteSpinnerWeekly)

        // Day checkboxes in 2 rows of 4 (Mon–Thu / Fri–Sun + empty cell)
        val daysPanel = JPanel(GridLayout(2, 4, 6, 4))
        dayCheckBoxes.forEach { daysPanel.add(it) }

        val lblDays = JLabel(text("MSG_SHD_DAYS"))
        val lblTime = JLabel(text("MSG_SHD_TIME"))
        val lblColon = JLabel(":")
        val lblStopTime = JLabel(text("MSG_SHD_STOP_TIME"))
        val lblStopColon = JLabel(":")
        stopLabelsWeekly.addAll(listOf(lblStopTime, lblStopColon))

        gbAdd(
            lblDays, panel,
            gridX = 0, gridY = 0,
            alignment = GridBagConstraints.EAST,
            padding = Insets(8, 10, 8, 8)
        )
        gbAdd(
            daysPanel, panel,
            gridX = 1, gridY = 0, colSpan = 3, weightX = 1.0,
            padding = Insets(8, 0, 8, 10),
            horizontalFill = true
        )

        gbAdd(
            lblTime, panel,
            gridX = 0, gridY = 1,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 10, 12, 8)
        )
        gbAdd(hourSpinnerWeekly, panel, gridX = 1, gridY = 1, padding = Insets(0, 0, 12, 4))
        gbAdd(lblColon, panel, gridX = 2, gridY = 1, padding = Insets(0, 0, 12, 4))
        gbAdd(minuteSpinnerWeekly, panel, gridX = 3, gridY = 1, padding = Insets(0, 0, 12, 10))

        gbAdd(
            chkStopWeekly, panel,
            gridX = 0, gridY = 2, colSpan = 4, weightX = 1.0,
            padding = Insets(0, 8, 8, 10),
            horizontalFill = true
        )

        gbAdd(
            lblStopTime, panel,
            gridX = 0, gridY = 3,
            alignment = GridBagConstraints.EAST,
            padding = Insets(0, 10, 12, 8)
        )
        gbAdd(stopHourSpinnerWeekly, panel, gridX = 1, gridY = 3, padding = Insets(0, 0, 12, 4))
        gbAdd(lblStopColon, panel, gridX = 2, gridY = 3, padding = Insets(0, 0, 12, 4))
        gbAdd(stopMinuteSpinnerWeekly, panel, gridX = 3, gridY = 3, padding = Insets(0, 0, 12, 10))

        return panel
    }

    /** Force the spinner text field to show exactly 2 characters wide. */
    private fun styleTimeSpinner(spinner: JSpinner) {
        (spinner.editor as JSpinner.DefaultEditor).textField.columns = 2
    }

    /** Greys out the stop-time controls of each card unless its checkbox is ticked. */
    private fun syncStopControls() {
        val oneTimeEnabled = chkStopOneTime.isSelected
        listOf(stopDateSpinner, stopHourSpinnerOneTime, stopMinuteSpinnerOneTime).forEach {
            it.isEnabled = oneTimeEnabled
        }
        stopLabelsOneTime.forEach { it.isEnabled = oneTimeEnabled }

        val weeklyEnabled = chkStopWeekly.isSelected
        listOf(stopHourSpinnerWeekly, stopMinuteSpinnerWeekly).forEach { it.isEnabled = weeklyEnabled }
        stopLabelsWeekly.forEach { it.isEnabled = weeklyEnabled }
    }

    /** If a schedule entry already exists for this download, pre-populate the UI. */
    private fun populateExistingEntry() {
        val entry = AppContext.scheduler.getEntryFor(downloadId) ?: return

        when (entry.scheduleType) {
            ScheduleType.ONE_TIME -> {
                radioOneTime.isSelected = true
                cardLayout.show(cardPanel, CARD_ONE_TIME)
                dateSpinner.value = Date(entry.epochMillis)
                val cal = Calendar.getInstance().apply { timeInMillis = entry.epochMillis }
                hourSpinnerOneTime.value = cal.get(Calendar.HOUR_OF_DAY)
                minuteSpinnerOneTime.value = cal.get(Calendar.MINUTE)

                chkStopOneTime.isSelected = entry.hasStopTime
                if (entry.hasStopTime && entry.stopEpochMillis > 0) {
                    stopDateSpinner.value = Date(entry.stopEpochMillis)
                    val stopCal = Calendar.getInstance().apply { timeInMillis = entry.stopEpochMillis }
                    stopHourSpinnerOneTime.value = stopCal.get(Calendar.HOUR_OF_DAY)
                    stopMinuteSpinnerOneTime.value = stopCal.get(Calendar.MINUTE)
                }
            }

            ScheduleType.WEEKLY -> {
                radioWeekly.isSelected = true
                cardLayout.show(cardPanel, CARD_WEEKLY)
                dayCheckBoxes.forEachIndexed { i, cb -> cb.isSelected = dayConstants[i] in entry.daysOfWeek }
                hourSpinnerWeekly.value = entry.hour
                minuteSpinnerWeekly.value = entry.minute

                chkStopWeekly.isSelected = entry.hasStopTime
                if (entry.hasStopTime) {
                    stopHourSpinnerWeekly.value = entry.stopHour
                    stopMinuteSpinnerWeekly.value = entry.stopMinute
                }
            }
        }
    }

    private fun onSchedule() {
        val entry: ScheduleEntry = if (radioOneTime.isSelected) {
            // Combine the date spinner's date with the separate hour/minute spinners
            val dateCal = Calendar.getInstance().apply { time = dateSpinner.value as Date }
            dateCal.set(Calendar.HOUR_OF_DAY, hourSpinnerOneTime.value as Int)
            dateCal.set(Calendar.MINUTE, minuteSpinnerOneTime.value as Int)
            dateCal.set(Calendar.SECOND, 0)
            dateCal.set(Calendar.MILLISECOND, 0)

            var stopMillis = -1L
            if (chkStopOneTime.isSelected) {
                val stopCal = Calendar.getInstance().apply { time = stopDateSpinner.value as Date }
                stopCal.set(Calendar.HOUR_OF_DAY, stopHourSpinnerOneTime.value as Int)
                stopCal.set(Calendar.MINUTE, stopMinuteSpinnerOneTime.value as Int)
                stopCal.set(Calendar.SECOND, 0)
                stopCal.set(Calendar.MILLISECOND, 0)
                if (stopCal.timeInMillis <= dateCal.timeInMillis) {
                    warn("MSG_SHD_MESSAGE2")
                    return
                }
                stopMillis = stopCal.timeInMillis
            }

            ScheduleEntry(
                downloadId = downloadId,
                scheduleType = ScheduleType.ONE_TIME,
                hour = dateCal.get(Calendar.HOUR_OF_DAY),
                minute = dateCal.get(Calendar.MINUTE),
                daysOfWeek = emptySet(),
                epochMillis = dateCal.timeInMillis,
                hasStopTime = chkStopOneTime.isSelected,
                stopEpochMillis = stopMillis,
            )
        } else {
            val selectedDays = dayCheckBoxes
                .mapIndexedNotNull { i, cb -> if (cb.isSelected) dayConstants[i] else null }
                .toSet()
            if (selectedDays.isEmpty()) {
                warn("MSG_SHD_MESSAGE1")
                return
            }
            val startHour = hourSpinnerWeekly.value as Int
            val startMinute = minuteSpinnerWeekly.value as Int
            val stopHour = stopHourSpinnerWeekly.value as Int
            val stopMinute = stopMinuteSpinnerWeekly.value as Int
            // A stop earlier than the start is a window running past midnight, which is allowed;
            // only an identical time is meaningless.
            if (chkStopWeekly.isSelected && stopHour == startHour && stopMinute == startMinute) {
                warn("MSG_SHD_MESSAGE2")
                return
            }
            ScheduleEntry(
                downloadId = downloadId,
                scheduleType = ScheduleType.WEEKLY,
                hour = startHour,
                minute = startMinute,
                daysOfWeek = selectedDays,
                epochMillis = -1L,
                hasStopTime = chkStopWeekly.isSelected,
                stopHour = stopHour,
                stopMinute = stopMinute,
            )
        }

        // Remove old entry (if any) then add the new one
        AppContext.scheduler.removeEntry(downloadId)
        AppContext.scheduler.addEntry(entry)
        dispose()
    }

    private fun warn(messageKey: String) {
        JOptionPane.showMessageDialog(
            this, text(messageKey), title, JOptionPane.WARNING_MESSAGE
        )
    }

    fun showDialog() {
        isVisible = true
    }

    companion object {
        private const val CARD_ONE_TIME = "ONE_TIME"
        private const val CARD_WEEKLY = "WEEKLY"
    }
}
