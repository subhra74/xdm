package xdman.preview;

import xdman.DownloadEntry;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.util.Logger;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
		Logger.log("Loading Dash http chunk", id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader bufferedReader = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);
			if (!f.exists()) {
				Logger.log("No saved Dash http chunk",
						f.getAbsolutePath());
				return null;
			} else {
				f = new File(f.getAbsolutePath(), "state.txt");
				if (!f.exists()) {
					Logger.log("No saved Dash http chunk state",
							f.getAbsolutePath());
					return null;
				} else {
					Logger.log("Loading Dash http chunk state...",
							f.getAbsolutePath());
					bufferedReader = XDMUtils.getBufferedReader(f);
					bufferedReader.readLine();
					bufferedReader.readLine();
					bufferedReader.readLine();
					bufferedReader.readLine();
					int chunkCount = Integer.parseInt(bufferedReader.readLine());
					for (int i = 0; i < chunkCount; i++) {
						String cid = bufferedReader.readLine();
						long len = Long.parseLong(bufferedReader.readLine());
						long off = Long.parseLong(bufferedReader.readLine());
						bufferedReader.readLine();
						String tag = bufferedReader.readLine();
						Chunk chunk = new Chunk();
						chunk.id = cid;
						chunk.length = len;
						chunk.startOff = off;
						chunk.tag = tag;
						list.add(chunk);
					}
					Collections.sort(list, new ChunkComparator());
				}
			}
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static List<Chunk> loadHLS(String id) {
		Logger.log("Loading HLS http chunk", id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader bufferedReader = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);

			if (!f.exists()) {
				Logger.log("No saved HLS http chunk",
						f.getAbsolutePath());
				return null;
			} else {
				f = new File(f.getAbsolutePath(), "state.txt");
				if (!f.exists()) {
					Logger.log("No saved HLS http chunk state",
							f.getAbsolutePath());
					return null;
				} else {
					Logger.log("Loading HLS http chunk state...",
							f.getAbsolutePath());
					bufferedReader = XDMUtils.getBufferedReader(f);
					bufferedReader.readLine();
					bufferedReader.readLine();
					bufferedReader.readLine();
					int urlCount = Integer.parseInt(bufferedReader.readLine());
					for (int i = 0; i < urlCount; i++) {
						bufferedReader.readLine();
					}
					int chunkCount = Integer.parseInt(bufferedReader.readLine());
					for (int i = 0; i < chunkCount; i++) {
						String cid = bufferedReader.readLine();
						long len = Long.parseLong(bufferedReader.readLine());
						long off = Long.parseLong(bufferedReader.readLine());
						bufferedReader.readLine();
						Chunk chunk = new Chunk();
						chunk.id = cid;
						chunk.length = len;
						chunk.startOff = off;
						list.add(chunk);
					}
					Collections.sort(list, new ChunkComparator());
				}
			}
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static List<Chunk> loadHttp(String id) {
		Logger.log("Loading http chunk", id);
		ArrayList<Chunk> list = new ArrayList<>();
		BufferedReader bufferedReader = null;
		try {
			DownloadEntry ent = XDMApp.getInstance().getEntry(id);
			if (ent == null) {
				return null;
			}
			String folder = ent.getTempFolder();
			File f = new File(folder, id);

			if (!f.exists()) {
				Logger.log("No saved http chunk",
						f.getAbsolutePath());
				return null;
			} else {
				f = new File(f.getAbsolutePath(), "state.txt");
				if (!f.exists()) {
					Logger.log("No saved http chunk state",
							f.getAbsolutePath());
					return null;
				} else {
					Logger.log("Loading http chunk state...",
							f.getAbsolutePath());
					bufferedReader = XDMUtils.getBufferedReader(f);
					bufferedReader.readLine();
					bufferedReader.readLine();
					int chunkCount = Integer.parseInt(bufferedReader.readLine());
					for (int i = 0; i < chunkCount; i++) {
						String cid = bufferedReader.readLine();
						long len = Long.parseLong(bufferedReader.readLine());
						long off = Long.parseLong(bufferedReader.readLine());
						bufferedReader.readLine();
						Chunk chunk = new Chunk();
						chunk.id = cid;
						chunk.length = len;
						chunk.startOff = off;
						list.add(chunk);
					}
					Collections.sort(list, new ChunkComparator());
				}
			}
			return list;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
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
