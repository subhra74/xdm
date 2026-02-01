package xdm.app.models;

import lombok.Builder;
import lombok.Getter;
import xdm.app.constants.FilterItemType;

import javax.swing.*;

@Builder
@Getter
public class FilterListItem {
  private FilterItemType itemType;
  private String text;
  private Icon icon;
  private Icon selectedIcon;
}
