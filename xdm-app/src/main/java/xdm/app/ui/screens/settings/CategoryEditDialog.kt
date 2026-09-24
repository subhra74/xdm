package xdm.app.ui.screens.settings

import xdm.app.AppContext
import xdm.app.DownloadCategory
import xdm.app.I8N
import xdm.app.ui.components.CategoryStyle
import xdm.app.utils.RemixIcon
import xdm.app.utils.chooseFile
import xdm.app.utils.createIcon
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Window
import java.io.File
import java.util.UUID
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.EmptyBorder

/**
 * Add/edit dialog for a single [DownloadCategory]: name, file types, target folder and the
 * icon+color the sidebar and list rows use. [result] is null when the user cancels.
 */
class CategoryEditDialog(parent: Window, private val existing: DownloadCategory?) : JDialog(parent) {
    var result: DownloadCategory? = null
        private set

    private val txtName = JTextField().apply { fixHeight(this) }
    private val txtTypes = JTextField().apply { fixHeight(this) }
    private val txtFolder = JTextField().apply { fixHeight(this) }
    private val cmbIcon = JComboBox(CategoryStyle.PICKABLE_ICONS.toTypedArray()).apply {
        renderer = IconChoiceRenderer()
        sizeTo(this, 76)
    }

    init {
        title = I8N.text(if (existing == null) "CAT_ADD" else "CAT_EDIT")
        isModal = true
        size = Dimension(500, 420)
        layout = BorderLayout()

        existing?.let {
            txtName.text = it.name
            txtTypes.text = DownloadCategory.formatExtensions(it.extensions)
            txtFolder.text = it.folder
            cmbIcon.selectedItem = CategoryStyle.iconName(it)
        }
        // A new category starts in the download folder; the user points it wherever they
        // want. Nothing re-derives it later, so renaming never moves a category's files.
        if (existing == null) txtFolder.text = AppContext.config.defaultDownloadFolder
        if (cmbIcon.selectedIndex < 0) cmbIcon.selectedIndex = 0

        val btnBrowse = JButton(
            I8N.text("SETTINGS_FOLDER_CHANGE"),
            createIcon(RemixIcon.FOLDER_6_LINE, 16, settingsAccentColor())
        ).apply {
            iconTextGap = 6
            fixHeight(this)
            addActionListener {
                val start = txtFolder.text.takeIf { t -> t.isNotBlank() }?.let { t -> File(t) }
                chooseFile(this@CategoryEditDialog, directoriesOnly = true, currentDir = start)?.let {
                    txtFolder.text = it.absolutePath
                }
            }
        }

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(14, 14, 10, 14)
            add(field(I8N.text("CAT_NAME"), txtName, null))
            add(Box.createRigidArea(Dimension(0, 10)))
            add(field(I8N.text("CAT_FILE_TYPES"), txtTypes, I8N.text("CAT_FILE_TYPES_HINT")))
            add(Box.createRigidArea(Dimension(0, 10)))
            add(field(I8N.text("CAT_FOLDER"), txtFolder, I8N.text("CAT_FOLDER_HINT")))
            add(Box.createRigidArea(Dimension(0, 6)))
            add(settingsLeftAligned(btnBrowse))
            add(Box.createRigidArea(Dimension(0, 10)))
            add(
                settingsRow(JLabel(I8N.text("CAT_APPEARANCE")), cmbIcon)
                    // Without a height cap the row stretches and squeezes the fields above it.
                    .apply { maximumSize = Dimension(Int.MAX_VALUE, 30) }
            )
            add(Box.createVerticalGlue())
        }

        val btnOk = JButton(I8N.text("DESC_SAVE_Q")).apply { addActionListener { onOk() } }
        val btnCancel = JButton(I8N.text("ND_CANCEL")).apply { addActionListener { dispose() } }
        rootPane.defaultButton = btnOk

        val buttons = Box.createHorizontalBox().apply {
            add(Box.createHorizontalGlue())
            add(btnOk)
            add(Box.createRigidArea(Dimension(10, 10)))
            add(btnCancel)
            padding(this, 10, topPadding = true)
            add(Box.createRigidArea(Dimension(10, 10)))
        }

        add(form, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
        setLocationRelativeTo(parent)
    }

    /** Pins a combo to a fixed width so the icon/color pickers stay compact. */
    private fun sizeTo(combo: JComboBox<*>, width: Int) {
        fixHeight(combo)
        combo.preferredSize = Dimension(width, combo.preferredSize.height)
        combo.maximumSize = Dimension(width, combo.preferredSize.height)
    }

    private fun field(label: String, input: JTextField, hint: String?): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, if (hint == null) 48 else 66)
        add(JLabel(label).apply { alignmentX = Component.LEFT_ALIGNMENT })
        add(Box.createRigidArea(Dimension(0, 4)))
        add(input.apply { alignmentX = Component.LEFT_ALIGNMENT })
        if (hint != null) {
            add(Box.createRigidArea(Dimension(0, 3)))
            add(JLabel(hint).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = settingsMutedColor()
                font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
            })
        }
    }

    private fun onOk() {
        val name = txtName.text.trim()
        if (name.isEmpty()) {
            showError(I8N.text("MSG_CAT_NAME_MISSING"))
            return
        }
        val extensions = DownloadCategory.parseExtensions(txtTypes.text)
        if (extensions.isEmpty()) {
            showError(I8N.text("MSG_CAT_FILE_TYPES_MISSING"))
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
            folder = folder,
            predefined = existing?.predefined ?: false,
            icon = (cmbIcon.selectedItem as RemixIcon).name,
        )
        dispose()
    }

    private fun showError(message: String) =
        javax.swing.JOptionPane.showMessageDialog(
            this, message, title, javax.swing.JOptionPane.WARNING_MESSAGE
        )

    /** Shows each glyph next to its name so the choice is visible before saving. */
    private class IconChoiceRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is RemixIcon && c is JLabel) {
                c.text = ""
                c.icon = createIcon(value, 16, null)
            }
            return c
        }
    }

}
