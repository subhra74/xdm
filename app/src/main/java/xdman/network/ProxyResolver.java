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

package xdman.network;

import xdman.Config;
import xdman.network.http.WebProxy;

public class ProxyResolver {

	public static WebProxy resolve(String url) {

		Config config = Config.getInstance();
		int proxyMode = config.getProxyMode();
		if (proxyMode == 2) {
			if (config.getProxyHost() == null || config.getProxyHost().length() < 1) {
				return null;
			}
			if (config.getProxyPort() < 1) {
				return null;
			}
			return new WebProxy(config.getProxyHost(), config.getProxyPort());
		}
		if (proxyMode == 3) {
			if (config.getSocksHost() == null || config.getSocksHost().length() < 1) {
				return null;
			}
			if (config.getSocksPort() < 1) {
				return null;
			}
			WebProxy wp = new WebProxy(config.getSocksHost(), config.getSocksPort());
			wp.setSocks(true);
			return wp;
		}
		return null;
	}

}
