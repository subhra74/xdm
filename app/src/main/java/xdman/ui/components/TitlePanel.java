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

package xdman.ui.components;

import java.awt.Component;
import java.awt.LayoutManager;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

import javax.swing.JPanel;

public class TitlePanel extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = -6469773600360331175L;
	Component parentWindow;
	int diffx, diffy;

	public TitlePanel(Component w) {
		super();
		parentWindow = w;
		registerMouseListener();
	}

	public TitlePanel(LayoutManager lm, Window w) {
		super(lm);
		parentWindow = w;
		registerMouseListener();
	}

	public void registerMouseListener() {
		this.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				diffx = me.getXOnScreen() - parentWindow.getLocationOnScreen().x;
				diffy = me.getYOnScreen() - parentWindow.getLocationOnScreen().y;
			}
		});

		this.addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseDragged(MouseEvent me) {
				parentWindow.setLocation(me.getXOnScreen() - diffx, me.getYOnScreen() - diffy);
			}
		});
	}
}
