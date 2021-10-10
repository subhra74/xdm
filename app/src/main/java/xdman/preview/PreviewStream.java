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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.tinylog.Logger;

import xdman.XDMApp;
import xdman.util.IOUtils;

@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class PreviewStream extends InputStream {

	private List<Chunk> chunks;
	private String currentId;
	private long read;
	private InputStream chunkStream;
	private final String id;
	private final String tag;
	private final int type;
	private Chunk currentChunk;

	public PreviewStream(String id, int type, String tag) {
		this.id = id;
		this.type = type;
		this.tag = tag;
	}

	public int read(byte[] buf, int off, int len) throws IOException {
		if (chunks == null) {
			chunks = ChunkLoader.load(id, type);
			if (chunks != null && chunks.size() > 0) {
				for (Chunk c : chunks) {
					if (tag != null && tag.equals(c.tag)) {
						currentChunk = c;
						currentId = c.id;
						break;
					} else if (tag == null) {
						currentChunk = c;
						currentId = c.id;
						break;
					}
				}
				openstream();
				read = 0;
			}
		}

		int r = -1;
		if (chunkStream != null) {
			r = chunkStream.read(buf, off, len);
		}
		if (r != -1) {
			read += r;
		}
		if (r == -1) {
			IOUtils.closeFlow(chunkStream);
			chunkStream = null;
			chunks = ChunkLoader.load(id, type);
			Chunk c = findCurrentChunk();
			if (c != null && read >= c.length) {
				Logger.info("Chunk finished, trying next chunk");
				c = findNext();
				currentId = c.id;
				openstream();
				read = 0;
				r = chunkStream.read(buf, off, len);
				if (r == -1) {
					IOUtils.closeFlow(chunkStream);
					return -1;
				}
			} else {
				Logger.info("Chunk is not finshed, sending 00000.....");
				int rem = (c.length - read) > buf.length ? buf.length : ((int) (c.length - read));
				r = rem;
				for (int i = 0; i < rem; i++) {
					buf[i] = 0;
				}
			}
			read += r;
			return r;
		}
		return r;
	}

	private Chunk findNext() {
		Chunk c = findCurrentChunk();
		int index = chunks.indexOf(c);
		for (int i = index + 1; i < chunks.size(); i++) {
			Chunk c2 = chunks.get(i);
			if (c != null && c.tag != null) {
				if (c.tag.equals(c2.tag)) {
					return c2;
				}
			} else {
				return c2;
			}
		}
		return null;
	}

	private Chunk findCurrentChunk() {
		for (Chunk c : chunks) {
			if (c.id.equals(currentId)) {
				return c;
			}
		}
		return null;
	}

	private void openstream() throws IOException {
		String tempFolder = XDMApp.getInstance().getEntry(id).getTempFolder();
		File tmpFolder = new File(tempFolder, id);
		chunkStream = new FileInputStream(new File(tmpFolder, currentId));
		Logger.info("Stream opened");
	}

	@Override
	public int read() throws IOException {
		byte[] buf = new byte[1];
		if (this.read(buf, 0, 1) == -1) {
			return -1;
		} else {
			return (buf[0] & 0xff);
		}
	}

	@Override
	public void close() throws IOException {
		IOUtils.closeFlow(chunkStream);
	}

}