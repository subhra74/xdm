package xdman.preview;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import xdman.DownloadEntry;
import xdman.XDMApp;
import xdman.XDMConstants;
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
		System.out.println("loading http chunk " + id);
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
			Collections.sort(list, new ChunkComparator());
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static List<Chunk> loadHLS(String id) {
		System.out.println("loading http chunk " + id);
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
			Collections.sort(list, new ChunkComparator());
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static List<Chunk> loadHttp(String id) {
		System.out.println("loading http chunk " + id);
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
			Collections.sort(list, new ChunkComparator());
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}

class ChunkComparator implements Comparator<Chunk> {

	@Override
	public int compare(Chunk c1, Chunk c2) {
		if (c1.startOff > c2.startOff) {
			return 1;
		} else if (c1.startOff < c2.startOff) {
			return -1;
		} else {
			return 0;
		}
	}
}
