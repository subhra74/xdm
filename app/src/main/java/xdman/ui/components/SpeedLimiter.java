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

import javax.swing.JComboBox;
import javax.swing.JOptionPane;

import xdman.Config;
import xdman.ui.res.StringResource;

public class SpeedLimiter {
	public static int getSpeedLimit() {
		int[] limitInts = { 0, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000 };
		String[] limits = { "Unlimited", "50k", "100k", "200k", "300k", "400k", "500k", "600k", "700k", "800k", "900k",
				"1000k" };
		JComboBox<String> cmb = new JComboBox<>(limits);
		Object[] components = { StringResource.get("MSG_SPEED_LIMIT"), cmb };
		int currentSpeedLimit = Config.getInstance().getSpeedLimit();
		for (int i = 0; i < limitInts.length; i++) {
			if (limitInts[i] == currentSpeedLimit) {
				cmb.setSelectedIndex(i);
			}
		}
		if (JOptionPane.showOptionDialog(null, components, StringResource.get("MENU_SPEED_LIMITER"),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, null, null) == JOptionPane.OK_OPTION) {
			String str = cmb.getSelectedItem() + "";
			int index = -1;
			int i = 0;
			for (String str2 : limits) {
				if (str.equals(str2)) {
					index = i;
					break;
				}
				i++;
			}
			return limitInts[index];
		}
		return -1;
	}
}