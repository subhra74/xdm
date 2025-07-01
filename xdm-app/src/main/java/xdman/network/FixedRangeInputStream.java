package xdman.network;

import java.io.IOException;
import java.io.InputStream;

public class FixedRangeInputStream extends InputStream {
	private InputStream baseStream;
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
