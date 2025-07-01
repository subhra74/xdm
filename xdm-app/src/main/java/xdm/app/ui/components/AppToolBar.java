package xdm.app.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import xdm.app.constants.DownloadEntryState;
import xdm.app.models.DownloadEntry;
import xdm.app.utils.AppUtils;
import xdman.ui.res.StringResource;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.function.Consumer;

public class AppToolBar {
  private JButton btnNew;
  private JButton btnPause;
  private JButton btnResume;
  private JButton btnDelete;
  private JButton btnOpen;
  private JButton btnOpenFolder;
  private JButton btnStopAll;
  private JButton btnStartAll;
  private JButton btnSettings;
  private JButton btnDeleteFinished;
  private JButton btnMenu;
  private JToolBar toolbar;
  private Component btnNewGap;
  private Component btnPauseGap;
  private Component btnResumeGap;
  private Component btnDeleteGap;
  private Component btnOpenGap;
  private Component btnOpenFolderGap;
  private Component btnStopAllGap;
  private Component btnStartAllGap;
  private Component btnSettingsGap;
  private Component btnDeleteFinishedGap;

  public AppToolBar(Consumer<String> searchCallback, ActionListener buttonCallback) {
    this.toolbar = new JToolBar();
    this.btnNew = createToolButton("add-large-fill.svg", buttonCallback, "TOOL_DOWNLOAD"); //
    this.btnNewGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnStopAll =
        createToolButton("delete-bin-line.svg", buttonCallback, "MENU_DELETE_COMPLETED"); // "Stop all");
    this.btnStopAllGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnStartAll =
        createToolButton("delete-bin-line.svg", buttonCallback, "MENU_DELETE_COMPLETED"); // "Start all");
    this.btnStartAllGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnSettings =
        createToolButton("sort-desc.svg", buttonCallback, "TOOL_SORT"); // "Settings");
    this.btnSettingsGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnPause =
        createToolButton("pause-large-fill.svg", buttonCallback, "MENU_PAUSE"); // "Pause");
    this.btnPauseGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnResume =
        createToolButton("play-large-line.svg", buttonCallback, "MENU_RESUME"); // "Resume");
    this.btnResumeGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnDelete =
        createToolButton("delete-bin-line.svg", buttonCallback, "DESC_DEL"); // "Delete");
    this.btnDeleteGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnDeleteFinished =
        createToolButton(
            "delete-bin-line.svg", buttonCallback, "MENU_DELETE_COMPLETED"); // "Delete finished");
    this.btnDeleteFinishedGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnOpen =
        createToolButton("share-box-line.svg", buttonCallback, "CTX_OPEN_FILE"); // "Open file");
    this.btnOpenGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnOpenFolder =
        createToolButton(
            "mail-open-line.svg", buttonCallback, "CTX_OPEN_FOLDER"); // "Open folder");
    this.btnOpenFolderGap = Box.createRigidArea(new Dimension(5, 0));
    this.btnMenu = createToolButton("menu-line.svg");

    toolbar.add(this.btnNew);
    toolbar.add(this.btnNewGap);

//    toolbar.add(this.btnStopAll);
//    toolbar.add(this.btnStopAllGap);

    toolbar.add(this.btnStartAll);
    toolbar.add(this.btnStartAllGap);

    toolbar.add(this.btnPause);
    toolbar.add(this.btnPauseGap);

    toolbar.add(this.btnResume);
    toolbar.add(this.btnResumeGap);

    toolbar.add(this.btnDelete);
    toolbar.add(this.btnDeleteGap);

    toolbar.add(this.btnOpen);
    toolbar.add(this.btnOpenGap);

    toolbar.add(this.btnOpenFolder);
    toolbar.add(this.btnOpenFolderGap);

    toolbar.add(this.btnSettings);
    toolbar.add(this.btnSettingsGap);

    toolbar.add(this.btnDeleteFinished);
    toolbar.add(this.btnDeleteFinishedGap);

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

    this.hideButtons();
  }

  public Component getComponent() {
    return toolbar;
  }

  public void updateButtons(List<DownloadEntry> selectedItems) {
    hideButtons();
    if (selectedItems.isEmpty()) {
      btnSettings.setVisible(true);
      btnSettingsGap.setVisible(true);
      btnStopAll.setVisible(true);
      btnStopAllGap.setVisible(true);
      btnStartAll.setVisible(true);
      btnStartAllGap.setVisible(true);
      return;
    }
    btnDelete.setVisible(true);
    btnDeleteGap.setVisible(true);
    if (selectedItems.size() == 1) {
      var state = selectedItems.get(0).getState();
      if (state != DownloadEntryState.FINISHED || state != DownloadEntryState.PAUSED) {
        btnPause.setVisible(true);
        btnPauseGap.setVisible(true);
      } else if (state == DownloadEntryState.FINISHED) {
        btnOpen.setVisible(true);
        btnOpenGap.setVisible(true);
        btnOpenFolder.setVisible(true);
        btnOpenFolderGap.setVisible(true);
      } else {
        btnResume.setVisible(true);
        btnResumeGap.setVisible(true);
      }
    }
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

  private void hideButtons() {
    this.btnPause.setVisible(false);
    this.btnPauseGap.setVisible(false);
    this.btnResume.setVisible(false);
    this.btnResumeGap.setVisible(false);
    this.btnDelete.setVisible(false);
    this.btnDeleteGap.setVisible(false);
    this.btnOpen.setVisible(false);
    this.btnOpenGap.setVisible(false);
    this.btnOpenFolder.setVisible(false);
    this.btnOpenFolderGap.setVisible(false);
    this.btnStopAll.setVisible(false);
    this.btnStopAllGap.setVisible(false);
    this.btnStartAll.setVisible(false);
    this.btnStartAllGap.setVisible(false);
    this.btnSettings.setVisible(false);
    this.btnSettingsGap.setVisible(false);
    this.btnDeleteFinished.setVisible(false);
    this.btnDeleteFinishedGap.setVisible(false);
  }
}
