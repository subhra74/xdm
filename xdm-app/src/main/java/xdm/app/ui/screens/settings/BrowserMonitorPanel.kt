package xdm.app.ui.screens.settings

import xdm.app.I8N
import xdm.app.utils.createSVGIcon
import xdm.app.utils.fixHeight
import xdm.app.utils.padding
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants

class BrowserMonitorPanel : JPanel() {
    private val txtFileExt = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val btnExtDef = JButton(I8N.text("DESC_DEF"))
    private val txtVidExt = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val btnVidExtDef = JButton(I8N.text("DESC_DEF"))
    private val cmbMinVidSize = JComboBox<Any?>().apply {
        fixHeight(this)
        preferredSize = Dimension(150, preferredSize.height)
        maximumSize = Dimension(150, preferredSize.height)
    }
    private val txtBlockedHosts = JTextArea().apply {
        rows = 3
        wrapStyleWord = true
        lineWrap = true
    }
    private val btnHostDef = JButton(I8N.text("DESC_DEF"))
    private val chkGetServerTime = JCheckBox(I8N.text("LBL_GET_TIMESTAMP")).apply {
        padding(this, 10)
        setAlignmentX(LEFT_ALIGNMENT)
    }

    init {
        setLayout(BoxLayout(this, BoxLayout.Y_AXIS))

        val lblBrowerMon = JLabel(I8N.text("BROWSER_MONITORING")).apply {
            setAlignmentX(LEFT_ALIGNMENT)
            padding(this, 10, topPadding = true)
            font = font.deriveFont(16.0f)
        }
        add(lblBrowerMon)

        val p1 = JPanel().apply {
            padding(this, 5, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p1)
        p1.setLayout(GridLayout(1, 4, 0, 20))

        val lblChrome = JLabel(createSVGIcon("chrome-fill.svg", 64, Color.GRAY)).apply {
            text = "Chrome"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblChrome)

        val lblFirefox = JLabel(createSVGIcon("firefox-fill.svg", 64, Color.GRAY)).apply {
            text = "Firefox"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblFirefox)

        val lblEdge = JLabel(createSVGIcon("edge-new-fill.svg", 64, Color.GRAY)).apply {
            text = "Edge"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblEdge)

        val lblOther = JLabel(createSVGIcon("global-fill.svg", 64, Color.GRAY)).apply {
            text = "Other"
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition = SwingConstants.BOTTOM
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        p1.add(lblOther)

        val lblFileExt = JLabel(I8N.text("DESC_FILETYPES")).apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblFileExt)

        add(JScrollPane(txtFileExt).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p2 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p2)
        p2.setLayout(BoxLayout(p2, BoxLayout.X_AXIS))

        p2.add(btnExtDef)

        val lblVidExt = JLabel(I8N.text("DESC_VIDEOTYPES")).apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblVidExt)

        add(JScrollPane(txtVidExt).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p3 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p3)
        p3.setLayout(BoxLayout(p3, BoxLayout.X_AXIS))

        p3.add(btnVidExtDef)

        val p4 = JPanel().apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p4)
        p4.setLayout(BoxLayout(p4, BoxLayout.X_AXIS))

        val lblMinVidSize = JLabel(I8N.text("LBL_MIN_VIDEO_SIZE"))
        p4.add(lblMinVidSize)
        p4.add(Box.createHorizontalGlue())
        p4.add(cmbMinVidSize)

        val lblBlockedHosts = JLabel(I8N.text("DESC_SITEEXCEPTIONS")).apply {
            padding(this, 10)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(lblBlockedHosts)

        add(JScrollPane(txtBlockedHosts).apply {
            setAlignmentX(LEFT_ALIGNMENT)
        })

        val p5 = JPanel().apply {
            padding(this, 10, topPadding = true)
            setAlignmentX(LEFT_ALIGNMENT)
        }
        add(p5)
        p5.setLayout(BoxLayout(p5, BoxLayout.X_AXIS))

        p5.add(btnHostDef)

        add(chkGetServerTime)
    }

    override fun getInsets(): Insets {
        return Insets(10, 10, 10, 10)
    }
}