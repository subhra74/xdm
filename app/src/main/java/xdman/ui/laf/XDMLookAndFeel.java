/*
 * Copyright (c)  Subhra Das Gupta
 *
 * This file is part of Xtreme Download Manager.
 *
 * Xtreme Download Manager is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Xtreme Download Manager is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with Xtream Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * 
 */

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
				XDMMenuUI.class.getName(), "CheckBoxMenuItemUI", XDMMenuItemUI.class.getName(), // "TreeUI",
				"SpinnerUI", XDMSpinnerUI.class.getName(), "ProgressBarUI", XDMProgressBarUI.class.getName(),
				"ComboBoxUI", XDMComboBoxUI.class.getName() });
		System.setProperty("xdm.defaulttheme", "true");

		UIManager.put("Table.focusCellHighlightBorder", new EmptyBorder(1, 1, 1, 1));
		UIManager.put("ComboBox.rendererUseListColors", Boolean.TRUE);
		UIManager.put("Slider.thumbWidth", Integer.valueOf(getScaledInt(4)));
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
