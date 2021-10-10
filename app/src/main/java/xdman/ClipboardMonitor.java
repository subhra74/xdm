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

package xdman;

import java.net.URL;

import org.tinylog.Logger;

import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class ClipboardMonitor implements Runnable {

	private static ClipboardMonitor _this;
	private String lastContent;
	private Thread t;

	private ClipboardMonitor() {
	}

	public static ClipboardMonitor getInstance() {
		if (_this == null) {
			_this = new ClipboardMonitor();
		}
		return _this;
	}

	public void startMonitoring() {
		try {
			if (this.t == null) {
				this.t = new Thread(this);
				this.t.start();
			}
		} catch (Exception e) {
			Logger.error(e);
		}

	}

	public void stopMonitoring() {
		try {
			if (this.t != null && this.t.isAlive()) {
				this.t.interrupt();
				this.t = null;
			}
		} catch (Exception e) {
			Logger.error(e);
		}

	}

	@Override
	public void run() {
		try {
			while (true) {
				String txt = XDMUtils.getClipBoardText();
				if (StringUtils.isNullOrEmptyOrBlank(txt)) {
					return;
				}
				if (!txt.equals(this.lastContent)) {
					Logger.info("New content: " + txt);
					this.lastContent = txt;
					try {
						new URL(txt);
						HttpMetadata md = new HttpMetadata();
						md.setUrl(txt);
						String file = XDMUtils.getFileName(txt);
						String extension = XDMUtils.getExtension(file);
						if (!StringUtils.isNullOrEmptyOrBlank(extension)) {
							extension = extension.toUpperCase().replace(".", "");
						}

						String[] arr = Config.getInstance().getFileExits();
						boolean found = false;
						for (String s : arr) {
							if (extension != null && s.contains(extension)) {
								found = true;
								break;
							}
						}
						if (found) {
							XDMApp.getInstance().addDownload(md, file);
						}
					} catch (Exception e) {
						Logger.error(e);
					}
				}
				Thread.sleep(1000);
			}
		} catch (Exception e) {
			Logger.error(e);
		}
	}

}
