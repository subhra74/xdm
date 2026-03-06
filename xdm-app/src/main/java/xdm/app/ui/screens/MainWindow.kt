package xdm.app.ui.screens

import xdm.app.ui.components.AppToolBar
import xdm.app.ui.components.FilterListPanel
import xdm.app.ui.components.MainListView
import java.util.function.Consumer
import javax.swing.JFrame

class MainWindow : JFrame() {
    private val listView = MainListView()

    init {
        val filterPanel = FilterListPanel()
        val toolbar = AppToolBar(Consumer { s: String? -> }, this)
        toolbar.setMultiSelectView(false)
    }
}