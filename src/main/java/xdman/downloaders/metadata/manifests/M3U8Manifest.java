package xdman.downloaders.metadata.manifests;

import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

public class M3U8Manifest {
	private String playlistUrl;
	private float duration;
	private ArrayList<String> mediaUrls;
	private boolean masterPlaylist;
	private boolean encrypted;
	private ArrayList<M3U8MediaInfo> mediaProperties;// valid only for master
	// playlist

	public M3U8Manifest(String file, String playlistUrl) throws Exception {
		this.playlistUrl = playlistUrl;
		this.mediaUrls = new ArrayList<>();
		this.mediaProperties = new ArrayList<>();
		ArrayList<String> urlList = parseManifest(file);
		makeMediaUrls(urlList);
	}

	public ArrayList<String> getMediaUrls() {
		return mediaUrls;
	}

	public M3U8MediaInfo getMediaProperty(int index) {
		return mediaProperties.get(index);
	}

	private void makeMediaUrls(ArrayList<String> list) throws Exception {
		String base_url = "";
		URI uri = null;
		for (int i = 0; i < list.size(); i++) {
			String item = list.get(i);
			String item_url = resolveURL(playlistUrl, item);
			if (item_url == null) {
				if (item.startsWith("/")) {
					if (StringUtils.isNullOrEmpty(base_url)) {
						if (uri == null) {
							uri = new URI(this.playlistUrl);
						}
						base_url = uri.getScheme() + "://" + uri.getHost() + ""
								+ (uri.getPort() > 0 ? ":" + uri.getPort() : "");
					}
					item_url = base_url + item;
				} else if (item.startsWith("http://") || item.startsWith("https://")) {
					item_url = item;
				} else {
					int index = this.playlistUrl.lastIndexOf('/');
					item_url = this.playlistUrl.substring(0, index) + "/";
					item_url += item;
				}
			}
			mediaUrls.add(item_url);
		}
	}

	private String resolveURL(String playlistUrl, String segmentUrl) {
		try {
			Logger.log("Resolving M3U8Manifest Segment URL...",
					playlistUrl,
					segmentUrl);
			if (!(segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://"))) {
				URI uri = new URI(playlistUrl);
				String url = uri.resolve(segmentUrl).normalize().toString();
				Logger.log("Resolved M3U8 Manifest Segment URL:", url);
				return url;
			} else {
				return segmentUrl;
			}
		} catch (Exception e) {
			e.printStackTrace();
			Logger.log(e);
		}
		return null;
	}

	private ArrayList<String> parseManifest(String manifestFilePath) throws IOException {
		File manifestFile = new File(manifestFilePath);
		if (!manifestFile.exists()) {
			Logger.log("No saved M3U8 Manifest",
					manifestFile.getAbsolutePath());
			return null;
		}
		ArrayList<String> urlList = new ArrayList<>();
		BufferedReader bufferedReader = null;
		try {
			Logger.log("Loading M3U8 Manifest...",
					manifestFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(manifestFile);
			boolean expect = false;
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				String highline = line.toUpperCase().trim();
				if (highline.length() < 1)
					continue;

				if (highline.startsWith("#EXT-X-KEY")) {
					Logger.log("Encrypted segment detected:", line);
					// encrypted = true;
					// break;
				}
				if (expect) {
					urlList.add(line.trim());
					expect = false;
				}
				if (highline.startsWith("#EXT-X-STREAM-INF")) {
					masterPlaylist = true;
					expect = true;
					String[] arr = highline.split(":");
					if (arr.length > 1) {
						mediaProperties.add(M3U8MediaInfo.parse(arr[1].trim()));
					}
				}
				if (highline.startsWith("#EXTINF")) {
					masterPlaylist = false;
					expect = true;
					try {
						String[] arr = highline.split(":");
						if (arr.length > 1) {
							mediaProperties.add(M3U8MediaInfo.parse(arr[1].trim()));
							String str = arr[1].trim().split(",")[0];
							duration += Float.parseFloat(str);
						}
					} catch (Exception e) {
						Logger.log(e);
					}

				}
			}
		} catch (Exception e) {
			String message = String.format("Unable to parse M3U8 Manifest %s %s",
					manifestFile.getAbsolutePath(),
					e.getMessage());
			IOException ioException = new IOException(message, e);
			Logger.log(ioException);
			throw ioException;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception e) {
					Logger.log(e);
				}
			}
		}

		return urlList;
	}

	public final float getDuration() {
		return duration;
	}

	public final boolean isMasterPlaylist() {
		return masterPlaylist;
	}

	public final boolean isEncrypted() {
		return encrypted;
	}

	public static class M3U8MediaInfo {
		private String resolution, bandwidth;

		public final String getResolution() {
			return resolution;
		}

		public final void setResolution(String resolution) {
			this.resolution = resolution;
		}

		public final String getBandwidth() {
			return bandwidth;
		}

		public final void setBandwidth(String bandwidth) {
			this.bandwidth = bandwidth;
		}

		@Override
		public String toString() {
			return String.format("bw: %s res: %s", bandwidth, resolution);
		}

		public static M3U8MediaInfo parse(String str) {
			String[] arr = str.split(",");
			M3U8MediaInfo info = new M3U8MediaInfo();
			for (int j = 0; j < arr.length; j++) {
				try {
					String ss = arr[j].toUpperCase();
					if (ss.startsWith("RESOLUTION")) {
						if (ss.contains("=")) {
							info.resolution = ss.split("=")[1].trim();
						}
					}
					if (ss.startsWith("BANDWIDTH")) {
						if (ss.contains("=")) {
							info.bandwidth = ss.split("=")[1].trim();
							int bps = 0;
							try {
								bps = Integer.parseInt(info.bandwidth);
								info.bandwidth = (bps / 1000) + " kbps";
							} catch (Exception e) {
							}
						}
					}
				} catch (Exception e) {
				}
			}
			return info;
		}
	}

	// public static void main(String[] args) throws Exception {
	// M3U8Manifest mf = new
	// M3U8Manifest("C:\\Users\\sd00109548\\Desktop\\test.m3u8",
	// "http://dfgdfgsdfg/");
	// int i = 0;
	// for (Iterator iterator = mf.getMediaUrls().iterator(); iterator
	// .hasNext();) {
	// String type = (String) iterator.next();
	// Logger.log(type);
	// Logger.log(mf.getMediaProperty(i));
	// i++;
	// }
	// }
}
