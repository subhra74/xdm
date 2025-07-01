//package xdm.app.ui.components;
//
//import xdm.app.utils.AppUtils;
//import xdman.ui.res.StringResource;
//
//import javax.swing.*;
//import java.awt.*;
//
//public class StatusPanel extends JPanel {
//  private final JButton btnToggle;
//  private final JLabel lblMonitoring;
//  private final JButton btnHelp;
//  private final JButton btnUpdate;
//
//  public StatusPanel() {
//    setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
//    btnToggle = new JButton();
//    btnToggle.setIcon(AppUtils.createSVGIcon("toggle-fill.svg", 16, new Color(30, 144, 255)));
//    btnUpdate = new JButton(StringResource.get("MENU_UPDATE"));
//    btnHelp = new JButton(StringResource.get("MENU_HELP_SUP"));
//    lblMonitoring = new JLabel(StringResource.get("BROWSER_MONITORING"));
//    this.add(lblMonitoring);
//    this.add(btnToggle);
//    this.add(Box.createVerticalGlue());
//    this.add(btnHelp);
//    this.add(btnUpdate);
//  }
//}
