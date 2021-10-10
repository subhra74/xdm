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

import java.awt.Component;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSpinnerUI;

import xdman.ui.components.CustomButton;
import xdman.ui.res.ColorResource;
import xdman.ui.res.ImageResource;

public class XDMSpinnerUI extends BasicSpinnerUI {
	public static ComponentUI createUI(JComponent c) {
		return new XDMSpinnerUI();
	}

	protected Component createNextButton() {
		CustomButton btn = new CustomButton();
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setContentAreaFilled(false);
		btn.setHorizontalAlignment(JButton.CENTER);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setBorderPainted(false);
		btn.setIcon(ImageResource.getIcon("up.png", 10, 10));
		btn.setName("Spinner.nextButton");
		installNextButtonListeners(btn);
		return btn;
	}

	protected Component createPreviousButton() {
		CustomButton btn = new CustomButton();
		btn.setBackground(ColorResource.getDarkBtnColor());
		btn.setContentAreaFilled(false);
		btn.setHorizontalAlignment(JButton.CENTER);
		btn.setMargin(new Insets(0, 0, 0, 0));
		btn.setBorderPainted(false);
		btn.setIcon(ImageResource.getIcon("down.png", 10, 10));
		btn.setName("Spinner.previousButton");
		installPreviousButtonListeners(btn);
		return btn;
	}

}
