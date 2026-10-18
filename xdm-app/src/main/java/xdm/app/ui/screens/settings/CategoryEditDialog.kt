package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N
import xdm.app.ui.components.AppTheme
import xdm.app.ui.components.CategoryDotIcon
import xdm.app.utils.ScaledEmptyBorder
import xdm.app.utils.chooseFile
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
import xdm.app.utils.px
import xdm.app.utils.scaledSize
import xdm.app.utils.scaledInsets
import xdm.app.utils.dialogHeight
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Window
import java.io.File
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Add/edit dialog for a single [DownloadCategory]: name, rules (file types, address and file name
 * patterns, Any/All), target folder and the color the sidebar dot and the list rows' tiles use.
 *
 * Settings opens it modal and reads [result] afterwards (null when cancelled). A download dialog's +
 * button opens it non-modal as its child and gets the saved category through [onSaved].
 */
class CategoryEditDialog(
    parent: Window,
    private val existing: DownloadCategory?,
    modal: Boolean = true,
    private val onSaved: ((DownloadCategory) -> Unit)? = null,
    /** The categories the name must not clash with; defaults to the saved ones. */
    private val others: () -> List<DownloadCategory> = { AppContext.config.categories },
) : JDialog(parent) {
    var result: DownloadCategory? = null
        private set

    // Columns: a long type list or folder path must not widen the dialog; BoxLayout stretches the fields.
    private val txtName = rounded(JTextField()).apply { columns = 10; fixHeight(this) }
    private val txtTypes = rounded(JTextField()).apply { columns = 10; fixHeight(this) }
    private val txtFolder = rounded(JTextField()).apply { columns = 10; fixHeight(this) }
    private val txtUrlPatterns = JTextArea(3, 10)
    private val txtNamePatterns = JTextArea(3, 10)
    private val cmbMatch = rounded(JComboBox(arrayOf(I8N.text("CAT_MATCH_ANY"), I8N.text("CAT_MATCH_ALL"))))
    /** The color the user picked, as `0xRRGGBB`; null keeps the theme's default for the category. */
    private var pickedColor: Int? = existing?.color

    /** A swatch of the category's color; clicking it opens the color picker. */
    private val btnColor = JButton().apply {
        toolTipText = I8N.text("CAT_COLOR_PICK")
        addActionListener { pickColor() }
    }

    init {
        title = I8N.text(if (existing == null) "CAT_ADD" else "CAT_EDIT")
        isModal = modal
        layout = BorderLayout()

        existing?.let {
            txtName.text = it.name
            txtTypes.text = DownloadCategory.formatExtensions(it.extensions)
            txtFolder.text = it.folder
            txtUrlPatterns.text = it.urlPatterns.joinToString("\n")
            txtNamePatterns.text = it.fileNamePatterns.joinToString("\n")
            cmbMatch.selectedIndex = if (it.matchAll) 1 else 0
        }
        // A new category starts in the download folder; the user points it wherever they
        // want. Nothing re-derives it later, so renaming never moves a category's files.
        if (existing == null) txtFolder.text = AppContext.config.defaultDownloadFolder
        refreshSwatch()

        // Hints live in placeholders and tooltips, so the dialog stays short.
        txtTypes.putClientProperty("JTextField.placeholderText", I8N.text("CAT_FILE_TYPES_PLACEHOLDER"))
        txtTypes.toolTipText = I8N.text("CAT_FILE_TYPES_HINT")
        txtUrlPatterns.toolTipText = I8N.text("CAT_URL_PATTERNS_HINT")
        txtNamePatterns.toolTipText = I8N.text("CAT_NAME_PATTERNS_HINT")
        txtFolder.toolTipText = I8N.text("CAT_FOLDER_HINT")

        val btnBrowse = settingsButton(I8N.text("SETTINGS_FOLDER_CHANGE")) {
            val start = txtFolder.text.takeIf { t -> t.isNotBlank() }?.let { t -> File(t) }
            chooseFile(this@CategoryEditDialog, directoriesOnly = true, currentDir = start)?.let {
                txtFolder.text = it.absolutePath
            }
        }

        // "All rules" only differs from "Any rule" once two kinds of rule are filled in.
        fun refreshMatchChoice() {
            val kinds = listOf(
                DownloadCategory.parseExtensions(txtTypes.text).isNotEmpty(),
                DownloadCategory.parsePatterns(txtUrlPatterns.text).isNotEmpty(),
                DownloadCategory.parsePatterns(txtNamePatterns.text).isNotEmpty(),
            ).count { it }
            cmbMatch.isEnabled = kinds >= 2
        }
        listOf(txtTypes.document, txtUrlPatterns.document, txtNamePatterns.document).forEach {
            it.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refreshMatchChoice()
                override fun removeUpdate(e: DocumentEvent) = refreshMatchChoice()
                override fun changedUpdate(e: DocumentEvent) = refreshMatchChoice()
            })
        }
        refreshMatchChoice()

        // One label/field grid: every row is one line tall except the two pattern boxes.
        val form = JPanel(GridBagLayout()).apply { border = ScaledEmptyBorder(14, 14, 6, 14) }
        var row = 0
        fun addRow(label: String, field: Component, extra: Component? = null) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHEAST
                insets = scaledInsets(9, 0, 4, 10)
            })
            form.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = row; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                gridwidth = if (extra == null) 2 else 1; insets = scaledInsets(4, 0, 4, 0)
            })
            extra?.let {
                form.add(it, GridBagConstraints().apply {
                    gridx = 2; gridy = row; insets = scaledInsets(4, 8, 4, 0); anchor = GridBagConstraints.WEST
                })
            }
            row++
        }
        addRow(I8N.text("CAT_NAME"), txtName)
        addRow(I8N.text("CAT_FILE_TYPES"), txtTypes)
        addRow(I8N.text("CAT_URL_PATTERNS"), patternBox(txtUrlPatterns))
        addRow(I8N.text("CAT_NAME_PATTERNS"), patternBox(txtNamePatterns))
        addRow(I8N.text("CAT_MATCH"), JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(cmbMatch.apply { fixHeight(this); maximumSize = preferredSize })
            add(Box.createRigidArea(scaledSize(16, 0)))
            add(JLabel(I8N.text("CAT_COLOR")))
            add(Box.createRigidArea(scaledSize(8, 0)))
            add(btnColor)
            add(Box.createHorizontalGlue())
        })
        addRow(I8N.text("CAT_FOLDER"), txtFolder, btnBrowse)
        form.add(JLabel(I8N.text("CAT_FOLDER_NEW_ONLY")).apply {
            foreground = settingsMutedColor()
            font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        }, GridBagConstraints().apply {
            gridx = 1; gridy = row; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = scaledInsets(0, 0, 4, 0)
        })

        val btnOk = settingsButton(I8N.text("DESC_SAVE_Q")) { onOk() }
        val btnCancel = settingsButton(I8N.text("ND_CANCEL")) { dispose() }
        rootPane.defaultButton = btnOk

        val buttons = Box.createHorizontalBox().apply {
            add(Box.createHorizontalGlue())
            add(btnOk)
            add(Box.createRigidArea(scaledSize(10, 10)))
            add(btnCancel)
            padding(this, 10, topPadding = true)
            add(Box.createRigidArea(scaledSize(10, 10)))
        }

        add(form, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        pack()
        // Never taller than the main window (or the screen); wide enough for a folder path.
        size = Dimension(maxOf(width, 520.px), minOf(height, dialogHeight(height)))
        setLocationRelativeTo(parent)
    }

    /** A two-line pattern box (one pattern per line) that scrolls beyond that. */
    private fun patternBox(area: JTextArea): JScrollPane {
        area.rows = 2
        area.font = txtName.font
        return JScrollPane(area)
    }

    private fun onOk() {
        val name = txtName.text.trim()
        if (name.isEmpty()) {
            showError(I8N.text("MSG_CAT_NAME_MISSING"))
            return
        }
        // Rules are optional: a category with none is only ever picked by hand.
        val extensions = DownloadCategory.parseExtensions(txtTypes.text)
        val clash = others().any { it.id != existing?.id && it.displayName.equals(name, ignoreCase = true) }
        if (clash) {
            showError(I8N.text("MSG_CAT_NAME_DUPLICATE"))
            return
        }
        val folder = txtFolder.text.trim()
        if (folder.isEmpty()) {
            showError(I8N.text("MSG_CAT_FOLDER_MISSING"))
            return
        }
        result = DownloadCategory(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            extensions = extensions,
            urlPatterns = DownloadCategory.parsePatterns(txtUrlPatterns.text),
            fileNamePatterns = DownloadCategory.parsePatterns(txtNamePatterns.text),
            matchAll = cmbMatch.isEnabled && cmbMatch.selectedIndex == 1,
            folder = folder,
            predefined = existing?.predefined ?: false,
            color = pickedColor,
        )
        result?.let { onSaved?.invoke(it) }
        dispose()
    }

    private fun showError(message: String) =
        javax.swing.JOptionPane.showMessageDialog(
            this, message, title, javax.swing.JOptionPane.WARNING_MESSAGE
        )

    /** The color shown on the swatch: the picked one, or the theme default for this category. */
    private fun currentColor(): Color =
        pickedColor?.let { Color(it) }
            ?: existing?.let { AppTheme.categoryColor(it.copy(color = null)) }
            ?: AppTheme.categoryColor(DownloadCategory(id = "new", name = "", extensions = emptySet(), folder = ""))

    private fun refreshSwatch() {
        val h = maxOf(cmbMatch.preferredSize.height, 22.px)
        btnColor.icon = CategoryDotIcon(currentColor(), boxSize = 18, dotSize = 14)
        btnColor.preferredSize = Dimension(h + 8.px, h)
        btnColor.maximumSize = btnColor.preferredSize
    }

    private fun pickColor() {
        JColorChooser.showDialog(this, I8N.text("CAT_COLOR_PICK"), currentColor())?.let {
            pickedColor = it.rgb and 0xFFFFFF
            refreshSwatch()
        }
    }

}
