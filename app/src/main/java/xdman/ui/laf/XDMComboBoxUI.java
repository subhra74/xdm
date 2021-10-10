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

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.ComboPopup;

import xdman.ui.components.CustomButton;
import xdman.ui.components.DarkScrollBar;
import xdman.ui.res.ColorResource;
import xdman.ui.res.ImageResource;

@SuppressWarnings("unused")
public class XDMComboBoxUI extends BasicComboBoxUI {

	static XDMComboBoxUI buttonUI;

	JComponent c;

	public static ComponentUI createUI(JComponent c) {
		return new XDMComboBoxUI();
	}

	protected JButton createArrowButton() {
		JButton button = new CustomButton();
		button.setBackground(ColorResource.getDarkBgColor());
		button.setIcon(ImageResource.getIcon("down.png", 10, 10));
		button.setBorderPainted(false);
		button.setFocusPainted(false);
		button.setName("ComboBox.arrowButton");
		return button;
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		this.c = c;
	}

	@Override
	protected ComboPopup createPopup() {
		return new BasicComboPopup(comboBox) {
			/**
			 * 
			 */
			private static final long serialVersionUID = -4232501153552563408L;

			@Override
			protected JScrollPane createScroller() {
				JScrollPane scroller = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
						JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
				scroller.setVerticalScrollBar(new DarkScrollBar(JScrollBar.VERTICAL));
				return scroller;
			}
		};
	}
}
