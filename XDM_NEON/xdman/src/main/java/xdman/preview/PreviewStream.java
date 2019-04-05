package xdman.preview;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import xdman.XDMApp;

public class PreviewStream extends InputStream {
	List<Chunk> chunks;
	String currentId;
	long read;
	InputStream chunkStream;
	String id, tag;
	int type;
	Chunk currentChunk;

	public PreviewStream(String id, int type, String tag) {
		this.id = id;
		this.type = type;
		this.tag = tag;
	}

	public int read(byte[] buf, int off, int len) throws IOException {
		if (chunks == null) {
			chunks = ChunkLoader.load(id, type);
			if (chunks.size() > 0) {
				for (int i = 0; i < chunks.size(); i++) {
					Chunk c = chunks.get(i);
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
		// System.out.println("data read : " + r);
		if (r == -1) {
			if (chunkStream != null) {
				chunkStream.close();
				chunkStream = null;
			}
			chunks = ChunkLoader.load(id, type);
			Chunk c = findCurrentChunk();
			if (read >= c.length) {
				System.out.println("Chunk finished, trying next chunk");
				c = findNext();
				currentId = c.id;
				openstream();
				read = 0;
				r = chunkStream.read(buf, off, len);
				if (r == -1) {
					chunkStream.close();
					return -1;
				}
				read += r;
				return r;
			} else {
				System.out.println("Chunk is not finshed, sending 00000.....");
				int rem = (c.length - read) > buf.length ? buf.length : ((int) (c.length - read));
				r = rem;
				for (int i = 0; i < rem; i++) {
					buf[i] = 0;
				}

				// c = findNext();
				// currentId = c.id;
				//
				// openstream();
				// read = 0;
				// r = chunkStream.read(buf, off, len);
				// if (r == -1) {
				// chunkStream.close();
				// return -1;
				// }
				read += r;
				return r;
			}
		}
		return r;
	}

	private Chunk findNext() {
		Chunk c = findCurrentChunk();
		int index = chunks.indexOf(c);
		for (int i = index + 1; i < chunks.size(); i++) {
			Chunk c2 = chunks.get(i);
			if (c.tag != null) {
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
		System.out.println("Stream opened");
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
		try {
			chunkStream.close();
		} catch (Exception e) {
			// TODO: handle exception
		}
	}
}