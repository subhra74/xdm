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
 * License along with Xtreme Download Manager; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package xdman.ui.components;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;

import org.tinylog.Logger;
import xdman.ui.res.StringResource;
import xdman.util.XDMUtils;

public class PopupAdapter extends MouseAdapter implements ActionListener {

	private JTextComponent txt;
	private JPopupMenu popup;

	public PopupAdapter(JTextComponent txt) {
		init();
		txt.addMouseListener(this);
		this.txt = txt;
	}

	public void uninstall() {
		this.txt.removeMouseListener(this);
		this.txt = null;
	}

	private void init() {
		popup = new JPopupMenu();
		popup.addPopupMenuListener(new PopupMenuListener() {

			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});
		JMenuItem menuCut = new JMenuItem(StringResource.get("CTX_CUT"));
		menuCut.addActionListener(this);
		menuCut.setName("MENU_CUT");
		JMenuItem menuCopy = new JMenuItem(StringResource.get("CTX_COPY"));
		menuCopy.addActionListener(this);
		menuCopy.setName("MENU_COPY");
		JMenuItem menuPaste = new JMenuItem(StringResource.get("CTX_PASTE"));
		menuPaste.setName("MENU_PASTE");
		menuPaste.addActionListener(this);
		JMenuItem menuSelect = new JMenuItem(StringResource.get("CTX_SELECT_ALL"));
		menuSelect.addActionListener(this);
		menuSelect.setName("MENU_SELECT_ALL");
		popup.add(menuCut);
		popup.add(menuCopy);
		popup.add(menuPaste);
		popup.add(menuSelect);
	}

	@Override
	public void mouseReleased(MouseEvent e) {
		if (e.getButton() == MouseEvent.BUTTON3 || SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()
				|| XDMUtils.isMacPopupTrigger(e)) {
			if (e.getSource() instanceof JTextComponent) {
				popup.show(txt, e.getX(), e.getY());
			}
		}
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (txt == null)
			return;
		Logger.info(txt);
		String name = ((JComponent) e.getSource()).getName();
		if ("MENU_CUT".equals(name)) {
			txt.cut();
		} else if ("MENU_COPY".equals(name)) {
			txt.copy();
		} else if ("MENU_SELECT_ALL".equals(name)) {
			txt.selectAll();
		} else if ("MENU_PASTE".equals(name)) {
			txt.paste();
		}
	}

}
