package xdman.downloaders;

import java.io.*;

import xdman.downloaders.http.HttpChannel;
import xdman.util.*;

public abstract class AbstractChannel implements Runnable {
	protected Segment chunk;
	private InputStream in;
	private byte[] buf;
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
			Logger.log("stopped chunk " + chunk);
			return false;
		}
		if (connectImpl()) {
			in = getInputStreamImpl();
			long length = getLengthImpl();
			if (chunk.getLength() < 0) {
				Logger.log("Setting length of " + chunk.getId() + " to: " + length);
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
				
				// do not proceed if chunk is stoppped
				if (chunk == null) {
					continue;
				}
				
				chunk.transferInitiated();
				if (((chunk.getLength() > 0) ? copyStream1() : copyStream2())) {
					Logger.log("Copy Stream finished");
					break;
				} else {
					Logger.log("Copy Stream not finished");
				}
			}
		} catch (Exception e) {
			Logger.log("Internal problem: " + e);
			Logger.log(e);
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
		Logger.log("Receiving by copyStream1");
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
						Logger.log(chunk + " complete and closing " + chunk.getDownloaded() + " " + chunk.getLength());
						return true;
					}
				}
				if (stop) {
					return false;
				}

				// if (this.socketDataRemaining == 0) {
				// // reuse socket and connect again as this connection has
				// // consumed all data
				// Logger.log(chunk + " length not satisfieble resending...");
				// return false;
				// }

				int diff = (int) (rem > buf.length ? buf.length : rem);
				// if (diff > this.socketDataRemaining) {
				// diff = (int) this.socketDataRemaining;
				// }

				int x = in.read(buf, 0, diff);
				if (stop)
					return false;
				if (x == -1) {
					Logger.log("Unexpected eof");
					throw new Exception("Unexpected eof - downloaded: " + chunk.getDownloaded() + " expected: "
							+ chunk.getLength());
				}
				chunk.getOutStream().write(buf, 0, x);
				if (stop)
					return false;
				chunk.setDownloaded(chunk.getDownloaded() + x);
				// this.socketDataRemaining -= x;
				chunk.transferring();
			}
			return false;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		} finally {
			close();
		}
	}

	private boolean copyStream2() {
		Logger.log("Receiving by copyStream2");
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
			Logger.log(e);
			return false;
		} finally {
			close();
		}
	}

	public int getErrorCode() {
		return errorCode;
	}

}
