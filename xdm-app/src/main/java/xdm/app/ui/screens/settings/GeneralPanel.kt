package xdm.app.ui.screens.settings

import xdm.app.I8N
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
import java.awt.Dimension
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class GeneralPanel : JPanel() {
    private val txtTmpDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val txtDwnDir = JTextField().apply {
        columns = 10
        fixHeight(this)
    }
    private val spModel = SpinnerNumberModel(100, 0, 10000000, 1)
    private val spnSpeedLimiter = JSpinner(spModel).apply {
        fixHeight(this)
        preferredSize = Dimension(120, preferredSize.height)
        maximumSize = Dimension(120, preferredSize.height)
    }
    private val chkSpeedLimiter = JCheckBox(I8N.text("SPEED_LIMIT_TITLE"))
    private val chkShowDwnPrg = JCheckBox(I8N.text("SHOW_DWN_PRG")).apply {
        padding(this, 10)
        alignmentX = LEFT_ALIGNMENT
    }
    private val chkShowComplete = JCheckBox(I8N.text("SHOW_DWN_COMPLETE")).apply { padding(this, 10) }
    private val chkStartAutoDwn = JCheckBox(I8N.text("LBL_START_AUTO")).apply { padding(this, 10) }
    private val chkOverwrite = JCheckBox(I8N.text("LBL_OVERWRITE_EXISTING")).apply { padding(this, 10) }
    private val btnBrowse1 = JButton("...")
    private val btnBrowse2 = JButton("...")
    private val cmbMaxConn = JComboBox<Any?>().apply {
        fixHeight(this)
    }

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        val lblTitle = JLabel(I8N.text("SETTINGS_GENERAL")).apply {
            padding(this, 10, topPadding = true)
            font = font.deriveFont(16.0f)
        }
        add(lblTitle)

        add(chkShowDwnPrg)

        add(chkShowComplete)

        add(chkStartAutoDwn)

        add(chkOverwrite)

        val panelSp = JPanel().apply { padding(this, 10) }
        panelSp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelSp)
        panelSp.setLayout(BoxLayout(panelSp, BoxLayout.X_AXIS))

        panelSp.add(chkSpeedLimiter)
        panelSp.add(Box.createHorizontalGlue())
        panelSp.add(spnSpeedLimiter)


        val lblTempFolder = JLabel(I8N.text("LBL_TEMP_FOLDER")).apply { padding(this, 5) }
        add(lblTempFolder)

        val panelTemp = JPanel().apply { padding(this, 10) }
        panelTemp.setAlignmentX(LEFT_ALIGNMENT)
        add(panelTemp)
        panelTemp.setLayout(BoxLayout(panelTemp, BoxLayout.X_AXIS))

        panelTemp.add(txtTmpDir)

        panelTemp.add(Box.createRigidArea(Dimension(10, 10)))

        panelTemp.add(btnBrowse1)

        val lblDefFolder = JLabel(I8N.text("SETTINGS_FOLDER")).apply { padding(this, 5) }
        add(lblDefFolder)

        val panelDefFolder = JPanel().apply { padding(this, 10) }
        panelDefFolder.setAlignmentX(LEFT_ALIGNMENT)
        add(panelDefFolder)
        panelDefFolder.setLayout(BoxLayout(panelDefFolder, BoxLayout.X_AXIS))

        panelDefFolder.add(txtDwnDir)

        panelDefFolder.add(Box.createRigidArea(Dimension(10, 10)))

        panelDefFolder.add(btnBrowse2)

        val panelMaxConn = Box.createHorizontalBox()
        panelMaxConn.setAlignmentX(LEFT_ALIGNMENT)
        add(panelMaxConn)

        val lblMaxConn = JLabel(I8N.text("MSG_MAX_DOWNLOAD"))
        panelMaxConn.add(lblMaxConn)
        panelMaxConn.add(Box.createHorizontalGlue())
        panelMaxConn.add(cmbMaxConn)

        add(Box.createHorizontalGlue())
    }

    override fun getInsets(): Insets {
        return Insets(10, 10, 10, 10)
    }
}