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

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTextAreaUI;
import javax.swing.text.JTextComponent;

import xdman.ui.components.PopupAdapter;

public class XDMTextAreaUI extends BasicTextAreaUI {
	PopupAdapter popupAdapter;

	public static ComponentUI createUI(JComponent c) {
		return new XDMTextAreaUI();
	}

	@Override
	public void installUI(JComponent c) {
		super.installUI(c);
		popupAdapter = new PopupAdapter((JTextComponent) c);
	}

	@Override
	public void uninstallUI(JComponent c) {
		super.uninstallUI(c);
		this.popupAdapter.uninstall();
	}
}
