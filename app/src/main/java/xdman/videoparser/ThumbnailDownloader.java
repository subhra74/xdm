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

package xdman.videoparser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.Config;
import xdman.network.http.JavaHttpClient;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

@SuppressWarnings({ "unused", "ResultOfMethodCallIgnored" })
public class ThumbnailDownloader implements Runnable {
	private final String[] thumbnails;
	private final long instanceKey;
	private boolean stop;
	private ThumbnailListener listener;

	public ThumbnailDownloader(ArrayList<String> list, ThumbnailListener listener, long instanceKey) {
		this.thumbnails = new String[list.size()];
		int i = 0;
		for (String str : list) {
			this.thumbnails[i++] = str;
		}
		this.listener = listener;
		this.instanceKey = instanceKey;
	}

	public void download() {
		Thread t = new Thread(this);
		t.start();
	}

	public void stop() {
		this.stop = true;
		this.listener = null;
	}

	public void removeThumbnailListener() {
		this.listener = null;
	}

	@Override
	public void run() {
		List<String> list = new ArrayList<>();
		try {
			if (this.thumbnails == null)
				return;
			for (String thumbnail : this.thumbnails) {
				if (this.stop)
					return;
				String file = downloadReal(thumbnail);
				if (this.stop)
					return;
				if (file != null) {
					if (this.listener != null) {
						this.listener.thumbnailsLoaded(this.instanceKey, thumbnail, file);
					}
					list.add(file);
				}
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			if (this.stop) {
				for (String file : list) {
					new File(file).delete();
				}
			}
		}
	}

	private String downloadReal(String url) {
		JavaHttpClient client = null;
		File tmpFile = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		FileOutputStream out = null;
		try {
			client = new JavaHttpClient(url);
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			if (this.stop) {
				return null;
			}
			Logger.info("manifest download response: " + resp);
			if (resp == 200 || resp == 206) {
				InputStream in = client.getInputStream();
				long len = client.getContentLength();
				out = new FileOutputStream(tmpFile);
				XDMUtils.copyStream(in, out, len);
				Logger.info("thumbnail download successful");
				return tmpFile.getAbsolutePath();
			}
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			if (client != null) {
				try {
					client.dispose();
				} catch (Exception e) {
					Logger.error(e);
				}
			}
			IOUtils.closeFlow(out);
			if (this.stop) {
				tmpFile.delete();
			}
		}
		return null;
	}
}
