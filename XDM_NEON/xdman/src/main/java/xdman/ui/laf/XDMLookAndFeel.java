package xdman.ui.laf;

import static xdman.util.XDMUtils.getScaledInt;

import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.metal.MetalLookAndFeel;

public class XDMLookAndFeel extends MetalLookAndFeel {

	private static final long serialVersionUID = 6437510613485554397L;

	public XDMLookAndFeel() {
		setCurrentTheme(new XDMTheme());
	}

	@Override
	public void initClassDefaults(UIDefaults table) {
		super.initClassDefaults(table);
		table.putDefaults(new Object[] { "ButtonUI", XDMButtonUI.class.getName(), "TextFieldUI",
				XDMTextFieldUI.class.getName(), "TextAreaUI", XDMTextAreaUI.class.getName(), "SliderUI",
				XDMSliderUI.class.getName(), "LabelUI", XDMLabelUI.class.getName(), "ScrollBarUI",
				XDMScrollBarUI.class.getName(), "MenuItemUI", XDMMenuItemUI.class.getName(), "MenuUI",
				XDMMenuUI.class.getName(), "CheckBoxMenuItemUI", XDMMenuItemUI.class.getName(), "TreeUI",
				XDMTreeUI.class.getName(), "SpinnerUI", XDMSpinnerUI.class.getName(), "ProgressBarUI",
				XDMProgressBarUI.class.getName(), "ComboBoxUI", XDMComboBoxUI.class.getName() });
		System.setProperty("xdm.defaulttheme", "true");

		UIManager.put("Table.focusCellHighlightBorder", new EmptyBorder(1, 1, 1, 1));
		UIManager.put("ComboBox.rendererUseListColors", Boolean.TRUE);
		UIManager.put("Slider.thumbWidth", Integer.valueOf(getScaledInt(4)));
		// UIManager.put("TabbedPane.selected", new Color(220, 220, 220));
		// UIManager.put("TabbedPane.borderHightlightColor", Color.LIGHT_GRAY);
		// UIManager.put("TabbedPane.contentAreaColor", Color.LIGHT_GRAY);
		// UIManager.put("TabbedPane.contentOpaque", Boolean.FALSE);
		// UIManager.put("OptionPane.background", new
		// ColorUIResource(Color.WHITE));
		// UIManager.put("Panel.background", new ColorUIResource(Color.WHITE));
		// UIManager.put("CheckBox.background", new
		// ColorUIResource(Color.WHITE));
		// UIManager.put("PopupMenu.border", new
		// LineBorder(ColorResource.getDarkBgColor()));
		// UIManager.put("PopupMenu.background",
		// ColorResource.getDarkerBgColor());
		// UIManager.put("MenuItem.selectionForeground", Color.WHITE);
		// UIManager.put("Menu.selectionForeground", Color.WHITE);
		// UIManager.put("Button.foreground", Color.WHITE);
		// UIManager.put("PopupMenuItem.selectionForeground", Color.WHITE);
		// UIManager.put("MenuItem.font", FontResource.getNormalFont());
		// UIManager.put("ComboBox.selectionBackground",
		// ColorResource.getSelectionColor());
		// UIManager.put("ComboBox.selectionForeground", Color.WHITE);
		// UIManager.put("ComboBox.disabledForeground", Color.GRAY);
		// UIManager.put("ComboBox.disabledBackground",
		// ColorResource.getDarkerBgColor());
		// UIManager.put("ComboBox.foreground", Color.WHITE);
		// UIManager.put("ComboBox.background",
		// ColorResource.getDarkerBgColor());
		// UIManager.put("Label.foreground", Color.WHITE);
		// UIManager.put("Panel.background", ColorResource.getDarkerBgColor());
		// UIManager.put("ScrollBar.width", new Integer(15));
		// UIManager.put("Popup.background", ColorResource.getDarkerBgColor());
		// UIManager.put("TextField.background",
		// ColorResource.getDarkerBgColor());
		// UIManager.put("TextField.border", new
		// LineBorder(ColorResource.getDarkBgColor(), 1));
		// UIManager.put("TextField.foreground", Color.WHITE);
		// UIManager.put("ComboBox.border", new
		// LineBorder(ColorResource.getDarkBgColor(), 1));
		// UIManager.put("TextField.selectionBackground",
		// ColorResource.getSelectionColor());
		// UIManager.put("TextField.selectionForeground", Color.WHITE);
		// UIManager.put("List.selectionBackground",
		// ColorResource.getSelectionColor());
		// UIManager.put("List.selectionForeground", Color.WHITE);
		// UIManager.put("List.focusCellHighlightBorder",
		// ColorResource.getSelectionColor());
		// UIManager.put("List.border", new LineBorder(Color.WHITE, 1));
		// UIManager.put("ScrollPane.border", new
		// LineBorder(ColorResource.getDarkBgColor(), 1));
		// UIManager.put("TableHeader.background",
		// ColorResource.getDarkerBgColor());
		// UIManager.put("TableHeader.cellBorder", new
		// LineBorder(ColorResource.getDarkBgColor(), 1));
		// UIManager.put("TableHeader.foreground", Color.WHITE);
	}

	protected void initComponentDefaults(UIDefaults table) {
		super.initComponentDefaults(table);
	}

	public String getName() {
		return "Default";
	}

	public String getID() {
		return "Default";
	}

	@Override
	public String getDescription() {
		return "Default theme for XDM";
	}

	@Override
	public boolean isNativeLookAndFeel() {
		return false;
	}

	@Override
	public boolean isSupportedLookAndFeel() {
		return true;
	}
}
