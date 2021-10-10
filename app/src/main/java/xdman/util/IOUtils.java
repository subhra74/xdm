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

package xdman.util;

import java.io.Closeable;
import java.io.IOException;

import org.tinylog.Logger;

/**
 * This class has functionalities to operate with outgoing and incoming flows.
 *
 * @version 1.0
 */
public class IOUtils {

	/**
	 * This method closes a data flow after verification.
	 *
	 * @param stream source where the data flow comes from
	 */
	public static void closeFlow(Closeable stream) {
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ex) {
				Logger.error(ex);
			}
		}
	}

}
