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

package xdman.preview;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.tinylog.Logger;

import xdman.DownloadEntry;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.util.IOUtils;
import xdman.util.XDMUtils;

public class ChunkLoader {

	static List<Chunk> load(String id, int type) {
		if (type == XDMConstants.HTTP) {
			return loadHttp(id);
		} else if (type == XDMConstants.DASH) {
			return loadDash(id);
		} else if (type == XDMConstants.HLS) {
			return loadHLS(id);
		}
		return null;
	}

	private static List<Chunk> loadDash(String id) {
		Logger.info("loading http chunk " + id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader br = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);
			if (f.exists()) {
				f = new File(f.getAbsolutePath(), "state.txt");
			}
			if (f.exists()) {
				br = new BufferedReader(new FileReader(f));
			} else {
				throw new IOException("State file not found");
			}
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = br.readLine();
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				br.readLine();
				String tag = br.readLine();
				Chunk chunk = new Chunk();
				chunk.id = cid;
				chunk.length = len;
				chunk.startOff = off;
				chunk.tag = tag;
				list.add(chunk);
			}
			list.sort(new ChunkComparator());
			return list;
		} catch (Exception e) {
			Logger.error(e);
			return null;
		} finally {
			IOUtils.closeFlow(br);
		}
	}

	private static List<Chunk> loadHLS(String id) {
		Logger.info("loading http chunk " + id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader br = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);
			if (f.exists()) {
				f = new File(f.getAbsolutePath(), "state.txt");
			}
			if (f.exists()) {
				br = new BufferedReader(new FileReader(f));
			} else {
				throw new IOException("state file not found");
			}
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			int urlCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < urlCount; i++) {
				br.readLine();
			}
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = br.readLine();
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				br.readLine();
				Chunk chunk = new Chunk();
				chunk.id = cid;
				chunk.length = len;
				chunk.startOff = off;
				list.add(chunk);
			}
			list.sort(new ChunkComparator());
			return list;
		} catch (Exception e) {
			Logger.error(e);
			return null;
		} finally {
			IOUtils.closeFlow(br);
		}
	}

	private static List<Chunk> loadHttp(String id) {
		Logger.info("loading http chunk " + id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader br = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);
			if (f.exists()) {
				f = new File(f.getAbsolutePath(), "state.txt");
			}
			if (f.exists()) {
				br = new BufferedReader(new FileReader(f));
			} else {
				throw new IOException("state file not found");
			}
			XDMUtils.readLineSafe(br);// br.readLine();
			XDMUtils.readLineSafe(br);// br.readLine();
			int chunkCount = Integer.parseInt(br.readLine());
			for (int i = 0; i < chunkCount; i++) {
				String cid = br.readLine();
				long len = Long.parseLong(br.readLine());
				long off = Long.parseLong(br.readLine());
				br.readLine();
				Chunk chunk = new Chunk();
				chunk.id = cid;
				chunk.length = len;
				chunk.startOff = off;
				list.add(chunk);
			}
			list.sort(new ChunkComparator());
			return list;
		} catch (Exception e) {
			Logger.error(e);
			return null;
		} finally {
			IOUtils.closeFlow(br);
		}
	}

}

class ChunkComparator implements Comparator<Chunk> {

	@Override
	public int compare(Chunk c1, Chunk c2) {
		return Long.compare(c1.startOff, c2.startOff);
	}

}
