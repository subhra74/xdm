package xdm.app.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import xdm.app.utils.AppUtils;
import xdman.ui.res.StringResource;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.function.Consumer;

public class AppToolBar {
  private JButton btnNew;
  private JButton btnSort;
  private JButton btnSettings;
  private JButton btnClear;
  private JButton btnMenu;
  private JButton btnDelete;
  private JToolBar toolbar;
  private Component btnNewGap;
  private Component btnSortGap;
  private Component btnSettingsGap;
  private Component btnClearGap;
  private Component btnDeleteGap;

  public AppToolBar(Consumer<String> searchCallback, ActionListener buttonCallback) {
    this.toolbar = new JToolBar();

    this.btnNew = createToolButton("add-large-fill.svg", buttonCallback, "TOOL_DOWNLOAD"); //
    this.btnNewGap = Box.createRigidArea(new Dimension(5, 0));

    this.btnClear =
        createToolButton(
            "delete-bin-line.svg", buttonCallback, "TOOL_CLEAR"); // "Delete finished");
    this.btnClearGap = Box.createRigidArea(new Dimension(5, 0));

    this.btnDelete = createToolButton("delete-bin-line.svg", buttonCallback, "TOOL_DELETE");
    this.btnDeleteGap = Box.createRigidArea(new Dimension(5, 0));

    this.btnSort = createToolButton("sort-desc.svg", buttonCallback, "TOOL_SORT"); // "Stop all");
    this.btnSortGap = Box.createRigidArea(new Dimension(5, 0));

    this.btnSettings =
        createToolButton("settings-4-line.svg", buttonCallback, "TOOL_SETTINGS"); // "Settings");
    this.btnSettingsGap = Box.createRigidArea(new Dimension(5, 0));

    this.btnMenu = createToolButton("menu-line.svg");

    toolbar.add(this.btnNew);
    toolbar.add(this.btnNewGap);

    toolbar.add(this.btnClear);
    toolbar.add(this.btnClearGap);

    toolbar.add(this.btnSort);
    toolbar.add(this.btnSortGap);

    toolbar.add(this.btnSettings);
    toolbar.add(this.btnSettingsGap);

    toolbar.add(this.btnDelete);
    toolbar.add(this.btnDeleteGap);

    toolbar.add(Box.createHorizontalGlue());

    var txtSearch = new JTextField(12);
    txtSearch.addActionListener(e -> searchCallback.accept(txtSearch.getText()));
    txtSearch.putClientProperty(FlatClientProperties.STYLE, "arc: 10");

    txtSearch.putClientProperty("JTextField.placeholderText", "Search");
    txtSearch.putClientProperty(
        "JTextField.trailingIcon", AppUtils.createSVGIcon("search-line.svg", 16, Color.GRAY));
    var d = txtSearch.getPreferredSize();
    var d2 = new Dimension(d.width, d.height + 5);
    txtSearch.setMaximumSize(d2);
    txtSearch.setPreferredSize(d2);
    toolbar.add(txtSearch);
    toolbar.add(Box.createRigidArea(new Dimension(5, 0)));
    toolbar.add(btnMenu);
    toolbar.add(Box.createRigidArea(new Dimension(5, 0)));
  }

  public Component getComponent() {
    return toolbar;
  }

  public void setMultiSelectView(boolean selectMode) {
    btnSettings.setVisible(!selectMode);
    btnSettingsGap.setVisible(!selectMode);

    btnClear.setVisible(!selectMode);
    btnClearGap.setVisible(!selectMode);

    btnDelete.setVisible(selectMode);
    btnDeleteGap.setVisible(selectMode);
  }

  private static JButton createToolButton(String icon, ActionListener buttonCallback, String key) {
    var btnNew = new JButton(StringResource.get(key));
    btnNew.setName(key);
    btnNew.setIconTextGap(8);
    btnNew.setIcon(AppUtils.createSVGIcon(icon, 16, Color.GRAY));
    btnNew.setForeground(Color.GRAY);
    btnNew.setMargin(new Insets(5, 5, 5, 5));
    btnNew.addActionListener(buttonCallback);
    return btnNew;
  }

  private static JButton createToolButton(String icon) {
    var btnNew = new JButton();
    btnNew.setIcon(AppUtils.createSVGIcon(icon, 16, Color.GRAY));
    btnNew.setForeground(Color.GRAY);
    btnNew.setMargin(new Insets(5, 5, 5, 5));
    return btnNew;
  }
}
