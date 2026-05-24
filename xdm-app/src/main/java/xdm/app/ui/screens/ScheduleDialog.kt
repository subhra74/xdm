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

class ScheduleDialog(parent: Window, private val downloadId: Long) :
    JDialog(parent, text("TITLE_SCHEDULER"), ModalityType.APPLICATION_MODAL) {

    // Schedule type radio buttons
    private val radioOneTime = JRadioButton("One-time")
    private val radioWeekly = JRadioButton("Weekly")

    // Card panel to swap between sub-panels
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    // ONE_TIME controls
    private val dateSpinner: JSpinner
    private val hourSpinnerOneTime: JSpinner
    private val minuteSpinnerOneTime: JSpinner

    // WEEKLY controls
    private val hourSpinnerWeekly: JSpinner
    private val minuteSpinnerWeekly: JSpinner

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

        dateSpinner = JSpinner(SpinnerDateModel(defaultDate, null, null, Calendar.DAY_OF_MONTH))
        hourSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultHour, 0, 23, 1))
        minuteSpinnerOneTime = JSpinner(SpinnerNumberModel(defaultMinute, 0, 59, 1))
        hourSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultHour, 0, 23, 1))
        minuteSpinnerWeekly = JSpinner(SpinnerNumberModel(defaultMinute, 0, 59, 1))

        initUI()
        populateExistingEntry()

        defaultCloseOperation = DISPOSE_ON_CLOSE
        //isResizable = false
        size = Dimension(500, 350)
        setLocationRelativeTo(parent)
    }

    private fun initUI() {
        val mainPanel = JPanel(GridBagLayout())
        mainPanel.border = EmptyBorder(15, 20, 10, 20)

        // --- Download info row ---
        val record = AppContext.db.getById(downloadId)
        val infoText = record?.fileName ?: "---"
        val lblDownload = JLabel("Download:")
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
        val lblType = JLabel("Type:")
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

        contentPane.add(mainPanel, BorderLayout.CENTER)

        // --- Button bar ---
        val btnBar = JPanel()
        btnBar.background = UIManager.getColor("Table.background")
        btnBar.border = EmptyBorder(10, 15, 10, 15)
        btnBar.layout = BoxLayout(btnBar, BoxLayout.X_AXIS)

        val btnCancel = JButton("Cancel")
        btnCancel.addActionListener { dispose() }

        val btnSchedule = JButton("Schedule")
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
        panel.border = BorderFactory.createTitledBorder("Date & Time")

        // Use a clean date-only format for the date spinner
        dateSpinner.editor = JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd")
        styleTimeSpinner(hourSpinnerOneTime)
        styleTimeSpinner(minuteSpinnerOneTime)

        val lblDate = JLabel("Date:")
        val lblTime = JLabel("Time:")
        val lblColon = JLabel(":")

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

        return panel
    }

    private fun buildWeeklyPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("Repeat On")

        styleTimeSpinner(hourSpinnerWeekly)
        styleTimeSpinner(minuteSpinnerWeekly)

        // Day checkboxes in 2 rows of 4 (Mon–Thu / Fri–Sun + empty cell)
        val daysPanel = JPanel(GridLayout(2, 4, 6, 4))
        dayCheckBoxes.forEach { daysPanel.add(it) }

        val lblDays = JLabel("Days:")
        val lblTime = JLabel("Time:")
        val lblColon = JLabel(":")

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

        return panel
    }

    /** Force the spinner text field to show exactly 2 characters wide. */
    private fun styleTimeSpinner(spinner: JSpinner) {
        (spinner.editor as JSpinner.DefaultEditor).textField.columns = 2
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
            }

            ScheduleType.WEEKLY -> {
                radioWeekly.isSelected = true
                cardLayout.show(cardPanel, CARD_WEEKLY)
                dayCheckBoxes.forEachIndexed { i, cb -> cb.isSelected = dayConstants[i] in entry.daysOfWeek }
                hourSpinnerWeekly.value = entry.hour
                minuteSpinnerWeekly.value = entry.minute
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

            ScheduleEntry(
                downloadId = downloadId,
                scheduleType = ScheduleType.ONE_TIME,
                hour = dateCal.get(Calendar.HOUR_OF_DAY),
                minute = dateCal.get(Calendar.MINUTE),
                daysOfWeek = emptySet(),
                epochMillis = dateCal.timeInMillis,
            )
        } else {
            val selectedDays = dayCheckBoxes
                .mapIndexedNotNull { i, cb -> if (cb.isSelected) dayConstants[i] else null }
                .toSet()
            if (selectedDays.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this, "Please select at least one day.", title, JOptionPane.WARNING_MESSAGE
                )
                return
            }
            ScheduleEntry(
                downloadId = downloadId,
                scheduleType = ScheduleType.WEEKLY,
                hour = hourSpinnerWeekly.value as Int,
                minute = minuteSpinnerWeekly.value as Int,
                daysOfWeek = selectedDays,
                epochMillis = -1L,
            )
        }

        // Remove old entry (if any) then add the new one
        AppContext.scheduler.removeEntry(downloadId)
        AppContext.scheduler.addEntry(entry)
        dispose()
    }

    fun showDialog() {
        isVisible = true
    }

    companion object {
        private const val CARD_ONE_TIME = "ONE_TIME"
        private const val CARD_WEEKLY = "WEEKLY"
    }
}
