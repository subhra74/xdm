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

package xdman.downloaders.hls;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.tinylog.Logger;

import xdman.util.FormatUtilities;
import xdman.util.IOUtils;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class PlaylistParser {

	public static HlsPlaylist parse(String file, String playlistUrl) {
		HlsPlaylist playlist = new HlsPlaylist();
		String keyUrl = null, IV = null;
		String url, resolution = null, bandwidth = null, sMediaSequence = null;
		boolean isMasterPlaylist = false, isEncryptedPlaylist = false;
		boolean isEncryptedSegment = false;
		int mediaSequence = 0;
		String duration = "";
		BufferedReader r = null;
		List<HlsPlaylistItem> items = new ArrayList<>();
		float totalDuration = 0.0f;
		try {
			r = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
			if (!XDMUtils.readLineSafe(r).startsWith("#EXTM3U")) {
				throw new IOException("Not a valid HLS manifest");
			}
			String prefixLine = "";
			while (true) {
				String line = r.readLine();
				if (line == null)
					break;
				line = line.trim();
				if (line.length() < 1)
					continue;
				if (line.endsWith("\\")) {
					prefixLine = line.substring(0, line.length() - 1);
					continue;
				} else {
					if (prefixLine.length() > 0) {
						line = prefixLine + " " + line;
						prefixLine = "";
					}
				}

				if (!line.startsWith("#")) {
					int segSeq;
					if (sMediaSequence != null) {
						segSeq = Integer.parseInt(sMediaSequence);
						if (mediaSequence == 0) {
							mediaSequence = segSeq;
						}
					}
					url = line;
					HlsPlaylistItem item = new HlsPlaylistItem(getAbsUrl(url, playlistUrl),
							isEncryptedSegment ? getAbsUrl(keyUrl, playlistUrl) : null,
							isEncryptedSegment ? getIV(IV, mediaSequence) : null, resolution, bandwidth, duration);
					items.add(item);
					mediaSequence++;

					sMediaSequence = resolution = bandwidth = null;
					try {
						if (!StringUtils.isNullOrEmptyOrBlank(duration)) {
							totalDuration += Float.parseFloat(duration);
						}
					} catch (Exception e) {
						Logger.error(e);
					}
					duration = "";
				} else if (line.startsWith("#EXT")) {
					if (line.startsWith("#EXT-X-STREAM-INF:")) {
						isMasterPlaylist = true;
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs.length > 0) {
								resolution = getAttrValue(attrs, "RESOLUTION");
								resolution = FormatUtilities.getResolution(resolution);
								bandwidth = getAttrValue(attrs, "BANDWIDTH");
								try {
									long bw = Integer.parseInt(bandwidth);
									bandwidth = (bw / 1000) + "k";
								} catch (Exception e) {
									Logger.error(e);
									bandwidth = "";
								}
							}
						}
					} else if (line.startsWith("#EXTINF:")) {
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs.length > 0) {
								String sDuration = attrs[0].trim();
								try {
									duration = sDuration;
								} catch (Exception e) {
									Logger.error(e);
								}
							}
						}
					} else if (line.startsWith("#EXT-X-BYTERANGE:")) {
						if (isEncryptedPlaylist) {
							throw new IOException("Encryption is not supported with byte range");
						}
					} else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs.length > 0) {
								sMediaSequence = attrs[0];
							}
						}
					} else if (line.startsWith("#EXT-X-KEY:")) {

						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs.length > 0) {
								String method = getAttrValue(attrs, "METHOD");
								keyUrl = getAttrValue(attrs, "URI");
								if (keyUrl != null) {
									keyUrl = keyUrl.replace("\"", "");
								}
								Logger.info("Method: " + method + " URI: " + keyUrl);
								if (method != null) {
									if (method.equals("AES-128") || method.equals("NONE")) {
										if (method.equals("AES-128")) {
											isEncryptedPlaylist = true;
											isEncryptedSegment = true;
											IV = getAttrValue(attrs, "IV");
											String keyFormat = getAttrValue(attrs, "KEYFORMAT");
											if (keyFormat != null && (!keyFormat.equals("identity"))) {
												Logger.warn("Unsupported encryption method: " + method + "/keyformat: "
														+ keyFormat);
												return null;
											}
										} else {
											isEncryptedSegment = false;
											Logger.info("Non encrypted");
										}
									} else {
										Logger.warn("Unsupported encryption method: " + method);
										return null;
									}

								}
							}
						}
					}
				}
			}
			playlist.setItems(items);
			playlist.setEncrypted(isEncryptedPlaylist);
			playlist.setMaster(isMasterPlaylist);
			playlist.setDuration(totalDuration);
			return playlist;
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(r);
		}
		return null;
	}

	private static String getKeyString(String line) {
		int index = line.indexOf(':');
		if (index < 0)
			return null;
		return line.substring(index + 1);
	}

	private static String getValue(String line) {
		int index = line.indexOf('=');
		if (index < 0)
			return null;
		return line.substring(index + 1);
	}

	private static String getAttrValue(String[] attrs, String name) {
		if (attrs != null) {
			for (String attr : attrs) {
				String attrib = attr.trim();
				if (attrib.startsWith(name)) {
					return getValue(attr);
				}
			}
		}
		return null;
	}

	private static String getAbsUrl(String chunkUrl, String playlistUrl) {
		return buildURL(playlistUrl, chunkUrl);
	}

	private static String buildURL(String playlistUrl, String segmentUrl) {
		if (segmentUrl == null) {
			return null;
		}
		try {
			if (!(segmentUrl.startsWith("http://") || segmentUrl.startsWith("https://"))) {
				URI uri = new URI(playlistUrl);
				return uri.resolve(segmentUrl).normalize().toString();
			} else {
				return segmentUrl;
			}
		} catch (Exception e) {
			Logger.error(e);
		}
		return null;
	}

	private static String getIV(String iv, int sequence) {
		if (StringUtils.isNullOrEmptyOrBlank(iv)) {
			return Integer.toHexString(sequence);
		}
		return iv;
	}

}