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

package xdman.monitoring;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.tinylog.Logger;

import xdman.XDMApp;
import xdman.downloaders.metadata.HdsMetadata;
import xdman.downloaders.metadata.manifests.F4MManifest;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class F4mHandler {

	public static boolean handle(File f4mfile, ParsedHookData data) {
		try {
			StringBuilder buf = new StringBuilder();
			InputStream in = new FileInputStream(f4mfile);
			BufferedReader r = new BufferedReader(new InputStreamReader(in));
			while (true) {
				String ln = r.readLine();
				if (ln == null) {
					break;
				}
				buf.append(ln).append("\n");
			}
			IOUtils.closeFlow(in);
			Logger.info("HDS manifest validating...");
			if (buf.indexOf("http://ns.adobe.com/f4m/1.0") < 0) {
				Logger.warn("No namespace");
				return false;
			}
			if (buf.indexOf("manifest") < 0) {
				Logger.warn("No manifest keyword");
				return false;
			}
			if (buf.indexOf("drmAdditional") > 0) {
				Logger.warn("DRM");
				return false;
			}
			if (buf.indexOf("media") == 0 || buf.indexOf("href") > 0 || buf.indexOf(".f4m") > 0) {
				Logger.warn("Not a valid manifest");
				return false;
			}

			Logger.info("URL: " + data.getUrl());
			F4MManifest manifest = new F4MManifest(data.getUrl(), f4mfile.getAbsolutePath());
			long[] bitRates = manifest.getBitRates();
			Logger.info("Bitrates: " + bitRates.length);
			for (long bitRate : bitRates) {
				HdsMetadata metadata = new HdsMetadata();
				metadata.setUrl(data.getUrl());
				metadata.setBitRate((int) bitRate);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".flv", "FLV " + bitRate + " bps");
			}
			Logger.info("Manifest valid");
			return true;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		}
	}

}
