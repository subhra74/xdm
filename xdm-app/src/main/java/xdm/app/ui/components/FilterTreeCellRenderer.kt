//package xdm.app.ui.components
//
//import xdm.app.utils.AppUtils
//import java.awt.Color
//import java.awt.Component
//import javax.swing.JLabel
//import javax.swing.JTree
//import javax.swing.UIManager
//import javax.swing.border.EmptyBorder
//import javax.swing.tree.TreeCellRenderer
//
//class FilterTreeCellRenderer : TreeCellRenderer {
//    override fun getTreeCellRendererComponent(
//        tree: JTree,
//        value: Any,
//        selected: Boolean,
//        expanded: Boolean,
//        leaf: Boolean,
//        row: Int,
//        hasFocus: Boolean
//    ): Component {
//        val lbl = JLabel(value.toString()).apply {
//            icon = AppUtils.createSVGIcon("file-zip-fill.svg", 24, Color.GRAY)
//            iconTextGap = 10
//            isOpaque = true
//            background = if (selected) UIManager.getColor("Tree.selectionBackground") else tree.background
//            foreground = if (selected) UIManager.getColor("Tree.selectionForeground") else tree.foreground
//            border = EmptyBorder(5, 5, 5, 5)
//        }
//        return lbl
//    }
//}
