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

import java.io.IOException;
import java.io.InputStream;

public class FixedRangeInputStream extends InputStream {

	private final InputStream baseStream;
	private long rem;

	public FixedRangeInputStream(InputStream baseStream, long length) {
		this.baseStream = baseStream;
		this.rem = length;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (rem == 0) {
			return -1;
		}
		if (rem > 0 && len > rem) {
			len = (int) rem;
		}
		int x = baseStream.read(b, off, len);
		if (x == -1) {
			if (rem > 0) {
				throw new IOException("Unexpected eof");
			} else
				return -1;
		}
		if (rem > 0) {
			rem -= x;
		}
		return x;
	}

	@Override
	public int read() throws IOException {
		if (rem == 0) {
			return -1;
		}
		int x = baseStream.read();
		if (x == -1) {
			if (rem > 0) {
				throw new IOException("Unexpected eof");
			} else
				return -1;
		}
		if (rem > 0) {
			rem -= x;
		}
		return x;
	}

	@Override
	public void close() throws IOException {
		baseStream.close();
	}

	public boolean isStreamFinished() {
		return rem == 0;
	}

}
