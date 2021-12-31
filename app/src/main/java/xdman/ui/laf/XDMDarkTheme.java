package xdman.ui.laf;

import java.awt.Color;

import javax.swing.UIDefaults;
import javax.swing.border.LineBorder;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.util.XDMUtils;

public class XDMDarkTheme extends AbstractXDMTheme {
	@Override
	public void addCustomEntriesToTable(UIDefaults table) {
		super.addCustomEntriesToTable(table);
		var selectionTextColor = new Color(0xecf0f1);
		var controlTextColor = new Color(0xAAAAAA);// new Color(0x72767E);
		var controlBackgroundColor = new Color(0x25262E);// new Color(0x2D3038);
		var editorBackgroundColor = new Color(0x2D3038);
		var activeButtonColor = new Color(0x3498db);
		var selectionColor = new Color(0x2980b9);// 0x373A42);
		var tableBackgroundColor = new Color(0x2D3038);
		var listBackgroundColor = new Color(0x2D3038);// new Color(0x1D1D1D);
		var scrollThumbColor = new Color(0x5A5A5A);
		table.put("Menu.foreground", controlTextColor);
		table.put("PopupMenu.border", new LineBorder(ColorResource.getDarkBgColor()));
		table.put("PopupMenu.background", controlBackgroundColor);
		table.put("MenuItem.foreground", controlTextColor);
		table.put("MenuItem.selectionForeground", Color.WHITE);
		table.put("Menu.selectionForeground", Color.WHITE);
		table.put("ComboBox.selectionBackground", ColorResource.getSelectionColor());
		table.put("ComboBox.selectionForeground", Color.WHITE);
		table.put("ComboBox.disabledForeground", Color.GRAY);
		table.put("ComboBox.disabledBackground", ColorResource.getDarkerBgColor());
		table.put("ComboBox.foreground", Color.WHITE);
		table.put("ComboBox.background", ColorResource.getDarkBgColor());

		table.put("Label.foreground", controlTextColor);
		table.put("Label.background", controlBackgroundColor);

		table.put("Panel.background", controlBackgroundColor);

		table.put("ScrollBar.width", Integer.valueOf(XDMUtils.getScaledInt(15)));
		table.put("Popup.background", ColorResource.getDarkerBgColor());

		table.put("TextArea.background", ColorResource.getDarkerBgColor());
		table.put("TextArea.foreground", Color.WHITE);
		table.put("TextArea.selectionBackground", ColorResource.getSelectionColor());
		table.put("TextArea.selectionForeground", Color.WHITE);
		table.put("TextArea.caretForeground", ColorResource.getSelectionColor());

		table.put("TextField.background", editorBackgroundColor);
		table.put("TextField.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("TextField.foreground", controlTextColor);
		table.put("TextField.selectionBackground", selectionColor);
		table.put("TextField.selectionForeground", selectionTextColor);
		table.put("TextField.caretForeground", selectionTextColor);

		table.put("PasswordField.background", ColorResource.getDarkerBgColor());
		table.put("PasswordField.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("PasswordField.foreground", Color.WHITE);
		table.put("PasswordField.selectionBackground", ColorResource.getSelectionColor());
		table.put("PasswordField.selectionForeground", Color.WHITE);
		table.put("PasswordField.caretForeground", ColorResource.getSelectionColor());

		table.put("ComboBox.border", new LineBorder(ColorResource.getDarkBgColor(), 1));

		table.put("List.selectionBackground", selectionColor);
		table.put("List.background", listBackgroundColor);
		table.put("List.foreground", controlTextColor);
		table.put("List.selectionForeground", selectionTextColor);
		table.put("List.focusCellHighlightBorder", selectionColor);
		table.put("List.border", new LineBorder(controlBackgroundColor, 0));

		table.put("Button.background", controlBackgroundColor);
		table.put("Button.foreground", controlTextColor);

		table.put("Table.selectionBackground", selectionColor);
		table.put("Table.background", tableBackgroundColor);
		table.put("Table.foreground", controlTextColor);
		table.put("Table.selectionForeground", selectionTextColor);
		table.put("Table.focusCellHighlightBorder", selectionColor);
		table.put("Table.border", new LineBorder(controlBackgroundColor, 0));

		table.put("ScrollPane.border", new LineBorder(controlBackgroundColor, 0));
		table.put("ScrollPane.background", controlBackgroundColor);
		table.put("ScrollPane.foreground", tableBackgroundColor);

		table.put("ScrollBar.background", controlBackgroundColor);
		table.put("ScrollBar.foreground", scrollThumbColor);

		table.put("TableHeader.background", controlBackgroundColor);
		table.put("TableHeader.cellBorder", new LineBorder(controlBackgroundColor, 1));
		table.put("TableHeader.foreground", controlTextColor);
		table.put("OptionPane.messageForeground", controlTextColor);
		table.put("OptionPane.background", ColorResource.getDarkerBgColor());

		table.put("Tree.textBackground", controlBackgroundColor);
		table.put("Tree.selectionBackground", selectionColor);
		table.put("Tree.selectionForeground", controlTextColor);
		table.put("Tree.selectionBorderColor", selectionColor);
		table.put("Tree.textForeground", controlTextColor);

		table.put("ToggleButton.background", ColorResource.getDarkerBgColor());
		table.put("ToggleButton.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("ToggleButton.foreground", Color.WHITE);
		table.put("ToggleButton.select", ColorResource.getSelectionColor());

		table.put("ToolTip.background", ColorResource.getDarkBgColor());
		table.put("ToolTip.foreground", Color.WHITE);
		table.put("ToolTip.font", FontResource.getItemFont());
	}
}
