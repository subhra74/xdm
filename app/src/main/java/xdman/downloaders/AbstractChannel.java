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

package xdman.downloaders;

import java.io.InputStream;

import org.tinylog.Logger;

import xdman.downloaders.http.HttpChannel;

public abstract class AbstractChannel implements Runnable {

	protected Segment chunk;
	private InputStream in;
	private final byte[] buf;
	protected volatile boolean stop;
	protected String errorMessage;
	private boolean closed;
	private Thread t;
	protected int errorCode;

	protected AbstractChannel(Segment chunk) {
		this.chunk = chunk;
		buf = new byte[8 * 8192];
	}

	public void open() {
		t = new Thread(this);
		t.setName(this.chunk.getId());
		t.start();
	}

	protected abstract boolean connectImpl();

	protected abstract InputStream getInputStreamImpl();

	protected abstract long getLengthImpl();

	protected abstract void closeImpl();

	private boolean connect() {
		try {
			chunk.getChunkListener().synchronize();
		} catch (NullPointerException e) {
			Logger.error(e);
			Logger.info("stopped chunk " + chunk);
			return false;
		}
		if (connectImpl()) {
			in = getInputStreamImpl();
			long length = getLengthImpl();
			if (chunk.getLength() < 0) {
				Logger.info("Setting length of " + chunk.getId() + " to: " + length);
				chunk.setLength(length);
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void run() {
		try {
			while (!stop) {
				if (!connect()) {
					if (!stop) {
						chunk.transferFailed(errorMessage);
					}
					close();
					break;
				}

				if (chunk == null) {
					continue;
				}

				chunk.transferInitiated();
				if (((chunk.getLength() > 0) ? copyStream1() : copyStream2())) {
					Logger.info("Copy Stream finished");
					break;
				} else {
					Logger.warn("Copy Stream not finished");
				}
			}
		} catch (Exception e) {
			Logger.warn("Internal problem: " + e);
			Logger.error(e);
			if (!stop) {
				chunk.transferFailed(errorMessage);
			}
		} finally {
			close();
		}
	}

	private void close() {
		if (closed)
			return;
		closeImpl();
		closed = true;
	}

	public void stop() {
		stop = true;
		this.chunk = null;
		if (this.t != null) {
			t.interrupt();
		}
	}

	private boolean copyStream1() {
		Logger.info("Receiving by copyStream1");
		try {
			while (!stop) {
				chunk.getChunkListener().synchronize();
				long rem = chunk.getLength() - chunk.getDownloaded();
				if (rem == 0) {
					if (this instanceof HttpChannel) {
						if (((HttpChannel) this).isFinished()) {
							close();
						}
					} else {
						close();
					}
					if (chunk.transferComplete()) {
						Logger.info(chunk + " complete and closing " + chunk.getDownloaded() + " " + chunk.getLength());
						return true;
					}
				}
				if (stop) {
					return false;
				}

				int diff = (int) (rem > buf.length ? buf.length : rem);

				int x = in.read(buf, 0, diff);
				if (stop)
					return false;
				if (x == -1) {
					Logger.warn("Unexpected eof");
					throw new Exception("Unexpected eof - downloaded: " + chunk.getDownloaded() + " expected: "
							+ chunk.getLength());
				}
				chunk.getOutStream().write(buf, 0, x);
				if (stop)
					return false;
				chunk.setDownloaded(chunk.getDownloaded() + x);
				chunk.transferring();
			}
			return false;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		} finally {
			close();
		}
	}

	private boolean copyStream2() {
		Logger.info("Receiving by copyStream2");
		try {
			while (!stop) {
				chunk.getChunkListener().synchronize();
				int x = in.read(buf, 0, buf.length);
				if (stop)
					return false;
				if (x == -1) {
					chunk.transferComplete();
					return true;
				}
				chunk.getOutStream().write(buf, 0, x);
				if (stop)
					return false;
				chunk.setDownloaded(chunk.getDownloaded() + x);
				chunk.transferring();
			}
			return false;
		} catch (Exception e) {
			Logger.error(e);
			return false;
		} finally {
			close();
		}
	}

	public int getErrorCode() {
		return errorCode;
	}

}
