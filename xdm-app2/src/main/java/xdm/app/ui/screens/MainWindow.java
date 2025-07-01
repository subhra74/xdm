package xdm.app.ui.screens;

import com.formdev.flatlaf.FlatClientProperties;
import xdm.app.models.Category;
import xdm.app.services.core.CategoryService;
import xdm.app.ui.components.CategoryTreeCellRenderer;
import xdm.app.ui.components.MainTableCellRenderer;
import xdm.app.ui.components.MainTableModel;
import xdm.app.util.AppUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;

public class MainWindow extends JFrame {
  private JTable table;

  public MainWindow() {
    // Set the title of the window
    super("XDM Application");

    // Set the default close operation
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    // Set the size of the window
    setSize(800, 500);

    // Center the window on the screen
    setLocationRelativeTo(null);

    add(rightPanel());
    add(createLeftPanel(), BorderLayout.WEST);
    setPreferredSize(getSize());
    pack();
    table.requestFocusInWindow();
    // Make the window visible
    setVisible(true);
  }

  private Component createLeftPanel() {
    var jpanel = new JPanel(new BorderLayout());
    var root = new DefaultMutableTreeNode();
    root.setUserObject("");

    var inProgressNode = new DefaultMutableTreeNode();
    inProgressNode.setUserObject("Unfinished");
    inProgressNode.setAllowsChildren(true);
    root.add(inProgressNode);

    var finishedNode = new DefaultMutableTreeNode();
    finishedNode.setUserObject("Finished");
    finishedNode.setAllowsChildren(true);
    root.add(finishedNode);

    for (Category category : CategoryService.INSTANCE.getAllCategories()) {
      var categoryNode = new DefaultMutableTreeNode();
      categoryNode.setUserObject(category.getDisplayText());
      finishedNode.add(categoryNode);
      categoryNode.setAllowsChildren(false);
    }

    var model = new DefaultTreeModel(root);

    var tree = new JTree(model);
    tree.setRootVisible(false);
    //tree.setShowsRootHandles(true);
    tree.setCellRenderer(new CategoryTreeCellRenderer());
    var jsp = new JScrollPane(tree);
    jsp.setPreferredSize(new Dimension(180, 100));
    jsp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");

    jpanel.setBorder(new EmptyBorder(10, 10, 10, 0));
    jpanel.add(jsp);
    return jpanel;
  }

  private JButton createToolButton(String icon, String text) {
    var btnNew = new JButton(text);
    btnNew.setIconTextGap(8);
    btnNew.setIcon(AppUtils.createSVGIcon(icon, 16, Color.GRAY));
    btnNew.setForeground(Color.GRAY);
    btnNew.setMargin(new Insets(5, 5, 5, 5));
    return btnNew;
  }

  Component createDownloadTable() {
    var renderer = new MainTableCellRenderer();
    var model = new MainTableModel();
    table = new JTable();
    table.setModel(model);

    table.setRowHeight(renderer.getPreferredHeight()); //32
    table.setDefaultRenderer(Object.class, renderer);
    table.setFillsViewportHeight(true);
    table.putClientProperty("TableHeader.cellMargins", new Insets(0, 10, 0, 0));
    var headerRenderer = (DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer();
    headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
    var jsp = new JScrollPane(table);
    jsp.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
    jsp.setAutoscrolls(true);
    return jsp;
  }

  private Component rightPanel() {
    var panel = new JPanel(new BorderLayout(10, 5));
    panel.add(createDownloadTable());

    var toolbar = new JToolBar();
    var button = createToolButton("add-large-fill.svg", "New");
    toolbar.add(button);
    toolbar.add(Box.createRigidArea(new Dimension(10, 10)));

    toolbar.add(createToolButton("pause-large-fill.svg", "Pause"));
    toolbar.add(Box.createRigidArea(new Dimension(10, 10)));

    toolbar.add(createToolButton("delete-bin-line.svg", "Delete"));
    toolbar.add(Box.createRigidArea(new Dimension(10, 10)));

    toolbar.add(createToolButton("sort-desc.svg", "Sort"));
    toolbar.add(Box.createRigidArea(new Dimension(10, 10)));

    toolbar.add(Box.createHorizontalGlue());

    var txtSearch = new JTextField(12);
    txtSearch.putClientProperty("JTextField.placeholderText", "Search");
    txtSearch.putClientProperty(FlatClientProperties.STYLE, "arc: 10");
    txtSearch.putClientProperty(
        "JTextField.trailingIcon", AppUtils.createSVGIcon("search-line.svg", 16, Color.GRAY));
    var d = txtSearch.getPreferredSize();
    var d2 = new Dimension(d.width, d.height + 5);
    txtSearch.setMaximumSize(d2);
    txtSearch.setPreferredSize(d2);
    toolbar.add(txtSearch);

    toolbar.add(Box.createRigidArea(new Dimension(5, 10)));
    toolbar.add(createToolButton("menu-line.svg", null));
    toolbar.setBorder(new EmptyBorder(System.getProperty("hidetop") == null ? 10 : 5, 0, 0, 0));

    panel.add(toolbar, BorderLayout.NORTH);
    panel.setBorder(new EmptyBorder(0, 10, 10, 10));
    return panel;
  }
}
