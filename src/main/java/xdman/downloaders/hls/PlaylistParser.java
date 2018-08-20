package xdman.downloaders.hls;

import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class PlaylistParser {

	public static HlsPlaylist parse(String filePath, String playlistUrl) {
		File hlsPlaylistFile = new File(filePath);
		if (!hlsPlaylistFile.exists()) {
			Logger.log("No saved HlsPlaylist",
					hlsPlaylistFile.getAbsolutePath());
			return null;
		}
		HlsPlaylist playlist = new HlsPlaylist();
		String keyUrl = null, IV = null;
		String url = null, resolution = null, bandwidth = null, sMediaSequence = null;
		boolean isMasterPlaylist = false, isEncryptedPlaylist = false;
		boolean isEncryptedSegment = false;
		int mediaSequence = 0;
		String duration = "";
		BufferedReader bufferedReader = null;
		List<HlsPlaylistItem> items = new ArrayList<>();
		float totalDuration = 0.0f;
		String lastUrl = null;
		boolean hasByteRange = false;
		try {
			Logger.log("Loading HlsPlaylist...",
					hlsPlaylistFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(hlsPlaylistFile);
			if (!bufferedReader.readLine().startsWith("#EXTM3U")) {
				throw new IOException("Not a valid HLS manifest");
			}
			String prefixLine = "";
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				line = line.trim();
				if (line.length() < 1)
					continue;
				if (line.endsWith("\\")) {
					prefixLine = line.substring(0, line.length() - 1);
					continue;
				} else {
					if (prefixLine.length() > 0) {
						line = String.format("%s %s", prefixLine, line);
						prefixLine = "";
					}
				}

				if (!line.startsWith("#")) {
					int segSeq = -1;
					if (sMediaSequence != null) {
						segSeq = Integer.parseInt(sMediaSequence);
						if (mediaSequence == 0) {
							mediaSequence = segSeq;
						}
					} else {
						segSeq = mediaSequence;
					}
					url = line;
					if (!(hasByteRange && url.equals(lastUrl))) {
						HlsPlaylistItem item = new HlsPlaylistItem(getAbsUrl(url, playlistUrl),
								isEncryptedSegment ? getAbsUrl(keyUrl, playlistUrl) : null,
								isEncryptedSegment ? getIV(IV, mediaSequence) : null, resolution, bandwidth, duration);
						items.add(item);
						mediaSequence++;
					}

					url = sMediaSequence = resolution = bandwidth = null;
					try {
						if (!StringUtils.isNullOrEmptyOrBlank(duration)) {
							totalDuration += Float.parseFloat(duration);
						}
					} catch (Exception e) {
						Logger.log(e);
					}
					duration = "";
				} else if (line.startsWith("#EXT")) {
					if (line.startsWith("#EXT-X-STREAM-INF:")) {
						isMasterPlaylist = true;
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs != null && attrs.length > 0) {
								resolution = getAttrValue(attrs, "RESOLUTION");
								resolution = FormatUtilities.getResolution(resolution);
								bandwidth = getAttrValue(attrs, "BANDWIDTH");
								try {
									long bw = Integer.parseInt(bandwidth);
									bandwidth = (bw / 1000) + "k";
								} catch (Exception e) {
									bandwidth = "";
								}
							}
						}
					} else if (line.startsWith("#EXTINF:")) {
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs != null && attrs.length > 0) {
								String sDuration = attrs[0].trim();
								try {
									duration = sDuration;
								} catch (Exception e) {
								}
							}
						}
					} else if (line.startsWith("#EXT-X-BYTERANGE:")) {
						hasByteRange = true;
						if (isEncryptedPlaylist) {
							throw new IOException("Encryption is not supported with byte range");
						}
					} else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs != null && attrs.length > 0) {
								sMediaSequence = attrs[0];
							}
						}
					} else if (line.startsWith("#EXT-X-KEY:")) {

						String attribSet = getKeyString(line);
						if (!StringUtils.isNullOrEmptyOrBlank(attribSet)) {
							String[] attrs = attribSet.split(",");
							if (attrs != null && attrs.length > 0) {
								String method = getAttrValue(attrs, "METHOD");
								keyUrl = getAttrValue(attrs, "URI");
								if (keyUrl != null) {
									keyUrl = keyUrl.replace("\"", "");
								}
								Logger.log("Method:", method, "URI:", keyUrl);
								if (method != null) {
									if (method.equals("AES-128") || method.equals("NONE")) {
										if (method.equals("AES-128")) {
											isEncryptedPlaylist = true;
											isEncryptedSegment = true;
											IV = getAttrValue(attrs, "IV");
											String keyFormat = getAttrValue(attrs, "KEYFORMAT");
											if (keyFormat != null && (!keyFormat.equals("identity"))) {
												Logger.log("Unsupported encryption method:", method, "keyformat:", keyFormat);
												return null;
											}
										} else {
											isEncryptedSegment = false;
											Logger.log("Non encrypted");
										}
									} else {
										Logger.log("Unsupported encryption method:", method);
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
			Logger.log("Failed to load saved state", e);
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception e2) {
					Logger.log(e2);
				}
			}
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

	private static String getAttrValue(String attrs[], String name) {
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
				String str = uri.resolve(segmentUrl).normalize().toString();
				return str;
			} else {
				return segmentUrl;
			}
		} catch (Exception e) {
			e.printStackTrace();
			Logger.log(e);
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