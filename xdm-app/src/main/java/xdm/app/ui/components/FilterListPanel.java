package xdm.app.ui.components;

import xdm.app.constants.FilterItemType;
import xdm.app.models.FilterListItem;
import xdm.app.utils.AppUtils;
import xdman.ui.res.StringResource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;

public class FilterListPanel {
  private final JScrollPane jsp;

  public FilterListPanel() {
    var box = Box.createVerticalBox();
    var stateFilterModel = new DefaultListModel<FilterListItem>();
    stateFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.ALL)
            .text(StringResource.get("CAT_ALL"))
            .icon(makeIcon("arrow-down-circle-fill.svg"))
            .build());
    stateFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.UNFINISHED)
            .text(StringResource.get("CAT_INCOMPLETE"))
            .icon(makeIcon("progress-2-fill.svg"))
            .build());
    stateFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.FINISHED)
            .text(StringResource.get("CAT_FINISHED"))
            .icon(makeIcon("checkbox-circle-fill.svg"))
            .build());
    var stateFilterList = new JList<>(stateFilterModel);
    stateFilterList.setOpaque(false);
    stateFilterList.setCellRenderer(new FilterListRenderer());
    stateFilterList.setAlignmentX(0);
    box.add(stateFilterList);

    var catFilterModel = new DefaultListModel<FilterListItem>();
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_ALL_TYPES)
            .text(StringResource.get("CAT_ALL_TYPES"))
            .icon(makeIcon("archive-2-fill.svg"))
            .build());
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_DOCUMENTS)
            .text(StringResource.get("CAT_DOCUMENTS"))
            .icon(makeIcon("file-list-2-fill.svg"))
            .build());
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_COMPRESSED)
            .text(StringResource.get("CAT_COMPRESSED"))
            .icon(makeIcon("file-zip-fill.svg"))
            .build());
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_MUSIC)
            .text(StringResource.get("CAT_MUSIC"))
            .icon(makeIcon("mv-fill.svg"))
            .build());
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_VIDEOS)
            .text(StringResource.get("CAT_VIDEOS"))
            .icon(makeIcon("movie-fill.svg"))
            .build());
    catFilterModel.addElement(
        FilterListItem.builder()
            .itemType(FilterItemType.CAT_PROGRAMS)
            .text(StringResource.get("CAT_PROGRAMS"))
            .icon(makeIcon("microsoft-fill.svg"))
            .build());

    var catFilterList = new JList<>(catFilterModel);
    catFilterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    catFilterList.setBorder(new EmptyBorder(10, 0, 0, 0));
    catFilterList.setOpaque(false);
    catFilterList.setCellRenderer(new FilterListRenderer());
    catFilterList.setAlignmentX(0);
    box.add(catFilterList);
    box.setBorder(new EmptyBorder(10, 0, 10, 10));
    box.setOpaque(true);
    box.setBackground(UIManager.getColor("Table.background"));
    jsp = new JScrollPane(box);
    jsp.setBorder(new MatteBorder(0, 0, 0, 1, Color.BLACK));
    jsp.setOpaque(false);
  }

  private Icon makeIcon(String icon) {
    return AppUtils.createSVGIcon(icon, 20, Color.GRAY);
  }

  public Component getComponent() {
    return this.jsp;
  }
}
