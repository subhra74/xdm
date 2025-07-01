package xdm.app.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import lombok.Getter;
import xdm.app.constants.FilterItemType;
import xdm.app.models.FilterItem;
import xdman.ui.res.StringResource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class FilterPanel {
  private final DefaultMutableTreeNode root;
  private final DefaultTreeModel filterModel;
  private final JTree filterTree;
  private final JScrollPane scrollPane;
  @Getter private FilterItem selection;

  public FilterPanel(Consumer<FilterItem> callback) {
    this.root = new DefaultMutableTreeNode();
    root.setUserObject(
        FilterItem.builder()
            .itemType(FilterItemType.ALL)
            .itemText(StringResource.get("ALL_DOWNLOADS"))
            .build());
    root.setAllowsChildren(true);

    var inProgressNode = new DefaultMutableTreeNode();
    inProgressNode.setUserObject(
        FilterItem.builder()
            .itemType(FilterItemType.UNFINISHED)
            .itemText(StringResource.get("ALL_UNFINISHED"))
            .build());
    inProgressNode.setAllowsChildren(true);
    makeCategories(inProgressNode, false);
    root.add(inProgressNode);

    var finishedNode = new DefaultMutableTreeNode();
    finishedNode.setUserObject(
        FilterItem.builder()
            .itemType(FilterItemType.FINISHED)
            .itemText(StringResource.get("ALL_FINISHED"))
            .build());
    finishedNode.setAllowsChildren(true);
    makeCategories(finishedNode, true);
    root.add(finishedNode);

    var queueNode = new DefaultMutableTreeNode();
    queueNode.setUserObject(
        FilterItem.builder()
            .itemType(FilterItemType.FINISHED)
            .itemText(StringResource.get("ALL_QUEUES"))
            .build());
    queueNode.setAllowsChildren(true);
    root.add(queueNode);

    //    final Map<String, Object> style = new HashMap<>();
    //    style.put("viewportBorder", new
    // MatteBorder(5,5,5,5,UIManager.getColor("Tree.background")));
    //    style.put("arc", 10);
    // this.scrollPane.putClientProperty("FlatLaf.style",style);
    // this.scrollPane.putClientProperty("FlatLaf.style","arc: 10");

    this.filterModel = new DefaultTreeModel(root, true);
    this.filterTree = new JTree(filterModel);
    this.filterTree.setCellRenderer(new FilterTreeCellRenderer());
    //this.filterTree.setShowsRootHandles(true);
    this.scrollPane = new JScrollPane(filterTree);
    //this.scrollPane.setViewportBorder(new MatteBorder(5, 5, 5, 5, UIManager.getColor("Tree.background")));
    this.scrollPane.setPreferredSize(new Dimension(180, 100));
//    this.scrollPane.setBorder(new LineBorder(UIManager.getColor("Component.borderColor"), 1));
    this.scrollPane.setBorder(new LineBorder(UIManager.getColor("Component.borderColor"), 1));

    this.filterTree
        .getSelectionModel()
        .addTreeSelectionListener(
            e -> {
              if (Objects.isNull(e.getNewLeadSelectionPath())) {
                return;
              }
              var node =
                  (DefaultMutableTreeNode) e.getNewLeadSelectionPath().getLastPathComponent();
              if (node != null) {
                var data = (FilterItem) node.getUserObject();
                this.selection = data;
                callback.accept(data);
              }
            });

    filterTree.setSelectionRow(0);
  }

  public Component getComponent() {
    return this.scrollPane;
  }

  private void makeCategories(DefaultMutableTreeNode node, boolean incomplete) {
    for (var str :
        new String[] {
          "CAT_DOCUMENTS", "CAT_COMPRESSED", "CAT_MUSIC", "CAT_VIDEOS", "CAT_PROGRAMS"
        }) {
      var catNode = new DefaultMutableTreeNode();
      catNode.setUserObject(
          FilterItem.builder()
              .itemType(
                  incomplete
                      ? FilterItemType.CATEGORY_UNFINISHED
                      : FilterItemType.CATEGORY_FINISHED)
              .itemText(StringResource.get(str))
              .build());
      catNode.setAllowsChildren(false);
      node.add(catNode);
    }
  }
}
