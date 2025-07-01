package xdman.ui.laf;

import java.awt.Color;

import javax.swing.UIDefaults;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.OceanTheme;

import xdman.ui.res.ColorResource;
import xdman.ui.res.FontResource;
import xdman.util.XDMUtils;

public class XDMTheme extends OceanTheme {
	FontUIResource fontResource;

	public XDMTheme() {
		fontResource = new FontUIResource(FontResource.getNormalFont());
	}

	@Override
	public FontUIResource getControlTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getWindowTitleFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getUserTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getSystemTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getSubTextFont() {
		return fontResource;
	}

	@Override
	public FontUIResource getMenuTextFont() {
		return fontResource;
	}

	Color gray = Color.BLACK, light_gray = Color.BLACK, lighter_gray = Color.BLACK;// new
																					// Color(230,
																					// 230,
																					// 230);

	ColorUIResource pm1 = new ColorUIResource(Color.BLACK);
	ColorUIResource pm2 = new ColorUIResource(Color.WHITE);// gray);
	ColorUIResource pm3 = new ColorUIResource(lighter_gray);

	ColorUIResource sc1 = new ColorUIResource(Color.BLACK);// BORDER COLOR
	ColorUIResource sc2 = new ColorUIResource(lighter_gray);// BUTTON LOWER
	// GRADIENT
	ColorUIResource sc3 = new ColorUIResource(Color.BLACK);// lighter_gray);//

	// BACKGROUND
	// COLOR

	@Override
	protected ColorUIResource getPrimary1() {
		return pm1;
	}

	@Override
	protected ColorUIResource getPrimary2() {
		return pm2;
	}

	@Override
	protected ColorUIResource getPrimary3() {
		return pm3;
	}

	@Override
	protected ColorUIResource getSecondary1() {
		return sc1;
	}

	@Override
	protected ColorUIResource getSecondary2() {
		return sc2;
	}

	@Override
	protected ColorUIResource getSecondary3() {
		return sc3;
	}

	// @Override
	// protected ColorUIResource getPrimary1() {
	// return sc1;
	// }
	@Override
	public void addCustomEntriesToTable(UIDefaults table) {
		super.addCustomEntriesToTable(table);
		table.put("Menu.foreground", ColorResource.getDeepFontColor());
		table.put("PopupMenu.border", new LineBorder(ColorResource.getDarkBgColor()));
		table.put("PopupMenu.background", ColorResource.getDarkerBgColor());
		table.put("MenuItem.foreground", ColorResource.getDeepFontColor());
		table.put("MenuItem.selectionForeground", Color.WHITE);
		table.put("Menu.selectionForeground", Color.WHITE);
		table.put("ComboBox.selectionBackground", ColorResource.getSelectionColor());
		table.put("ComboBox.selectionForeground", Color.WHITE);
		table.put("ComboBox.disabledForeground", Color.GRAY);
		table.put("ComboBox.disabledBackground", ColorResource.getDarkerBgColor());
		table.put("ComboBox.foreground", Color.WHITE);
		table.put("ComboBox.background", ColorResource.getDarkBgColor());
		table.put("Label.foreground", Color.WHITE);
		table.put("Panel.background", ColorResource.getDarkerBgColor());
		table.put("ScrollBar.width", Integer.valueOf(XDMUtils.getScaledInt(15)));
		table.put("Popup.background", ColorResource.getDarkerBgColor());

		table.put("TextArea.background", ColorResource.getDarkerBgColor());
		table.put("TextArea.foreground", Color.WHITE);
		table.put("TextArea.selectionBackground", ColorResource.getSelectionColor());
		table.put("TextArea.selectionForeground", Color.WHITE);
		table.put("TextArea.caretForeground", ColorResource.getSelectionColor());

		table.put("TextField.background", ColorResource.getDarkerBgColor());
		table.put("TextField.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("TextField.foreground", Color.WHITE);
		table.put("TextField.selectionBackground", ColorResource.getSelectionColor());
		table.put("TextField.selectionForeground", Color.WHITE);
		table.put("TextField.caretForeground", ColorResource.getSelectionColor());

		table.put("PasswordField.background", ColorResource.getDarkerBgColor());
		table.put("PasswordField.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("PasswordField.foreground", Color.WHITE);
		table.put("PasswordField.selectionBackground", ColorResource.getSelectionColor());
		table.put("PasswordField.selectionForeground", Color.WHITE);
		table.put("PasswordField.caretForeground", ColorResource.getSelectionColor());

		table.put("ComboBox.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("List.selectionBackground", ColorResource.getSelectionColor());
		table.put("List.selectionForeground", Color.WHITE);
		table.put("List.focusCellHighlightBorder", ColorResource.getSelectionColor());
		table.put("List.border", new LineBorder(Color.WHITE, 1));
		table.put("ScrollPane.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("TableHeader.background", ColorResource.getDarkerBgColor());
		table.put("TableHeader.cellBorder", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("TableHeader.foreground", Color.WHITE);
		table.put("OptionPane.messageForeground", Color.WHITE);
		table.put("OptionPane.background", ColorResource.getDarkerBgColor());

		table.put("Tree.textBackground", ColorResource.getDarkestBgColor());
		table.put("Tree.selectionBackground", ColorResource.getSelectionColor());
		table.put("Tree.selectionForeground", Color.WHITE);
		table.put("Tree.selectionBorderColor", ColorResource.getSelectionColor());
		table.put("Tree.textForeground", Color.WHITE);

		table.put("ToggleButton.background", ColorResource.getDarkerBgColor());
		table.put("ToggleButton.border", new LineBorder(ColorResource.getDarkBgColor(), 1));
		table.put("ToggleButton.foreground", Color.WHITE);
		table.put("ToggleButton.select", ColorResource.getSelectionColor());

		table.put("ToolTip.background", ColorResource.getDarkBgColor());
		table.put("ToolTip.foreground", Color.WHITE);
		table.put("ToolTip.font", FontResource.getItemFont());
	}

}
