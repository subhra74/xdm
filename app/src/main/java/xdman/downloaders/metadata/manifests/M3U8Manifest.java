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

package xdman.downloaders.metadata.manifests;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;

import org.tinylog.Logger;

import xdman.util.IOUtils;
import xdman.util.StringUtils;

@SuppressWarnings("unused")
public class M3U8Manifest {

	private final String playlistUrl;
	private float duration;
	private final ArrayList<String> mediaUrls;
	private boolean masterPlaylist;
	private boolean encrypted;
	private final ArrayList<M3U8MediaInfo> mediaProperties;// valid only for master

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
		for (String item : list) {
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
			Logger.info("Manifest Segment parsing ");
			if (!(segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://"))) {
				URI uri = new URI(playlistUrl);
				String str = uri.resolve(segmentUrl).normalize().toString();
				Logger.info("Manifest Segment parsing: " + str);
				return str;
			} else {
				return segmentUrl;
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return null;
	}

	private ArrayList<String> parseManifest(String file) throws IOException {
		ArrayList<String> urlList = new ArrayList<>();
		BufferedReader r = null;
		try {
			r = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
			boolean expect = false;
			while (true) {
				String line = r.readLine();
				if (line == null)
					break;
				String highline = line.toUpperCase().trim();
				if (highline.length() < 1)
					continue;

				if (highline.startsWith("#EXT-X-KEY")) {
					Logger.info("Encrypted segment detected: " + line);
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
						Logger.error(e);
					}

				}
			}
		} catch (Exception e) {
			Logger.error(e);
			throw new IOException("Unable to parse menifest");
		} finally {
			IOUtils.closeFlow(r);
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
			return "bw: " + bandwidth + " res: " + resolution;
		}

		public static M3U8MediaInfo parse(String str) {
			String[] arr = str.split(",");
			M3U8MediaInfo info = new M3U8MediaInfo();
			for (String s : arr) {
				try {
					String ss = s.toUpperCase();
					if (ss.startsWith("RESOLUTION")) {
						if (ss.contains("=")) {
							info.resolution = ss.split("=")[1].trim();
						}
					}
					if (ss.startsWith("BANDWIDTH")) {
						if (ss.contains("=")) {
							info.bandwidth = ss.split("=")[1].trim();
							int bps;
							try {
								bps = Integer.parseInt(info.bandwidth);
								info.bandwidth = (bps / 1000) + " kbps";
							} catch (Exception e) {
								Logger.error(e);
							}
						}
					}
				} catch (Exception e) {
					Logger.error(e);
				}
			}
			return info;
		}
	}

}
