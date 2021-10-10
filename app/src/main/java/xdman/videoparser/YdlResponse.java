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

package xdman.videoparser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.tinylog.Logger;

import xdman.network.http.HttpHeader;
import xdman.util.FormatUtilities;
import xdman.util.StringUtils;

@SuppressWarnings("unused")
public class YdlResponse {
	public static final int DASH_HTTP = 99, HTTP = 98, HLS = 97, HDS = 96;
	private static final int DASH_VIDEO_ONLY = 23;
	private static final int DASH_AUDIO_ONLY = 24;

	@SuppressWarnings("unchecked")
	public static ArrayList<YdlVideo> parse(InputStream in) throws Exception {
		JSONParser parser = new JSONParser();
		JSONObject obj = (JSONObject) parser.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		JSONArray entries = (JSONArray) obj.get("entries");
		if (entries == null) {
			Logger.warn("no playlist entry");
			entries = new JSONArray();
			entries.add(obj);
		}
		ArrayList<YdlVideo> playList = new ArrayList<>();
		for (Object entry : entries) {
			JSONObject jsobj = (JSONObject) entry;
			if (jsobj != null) {
				YdlVideo v = getPlaylistEntry(jsobj);
				if (v != null) {
					playList.add(v);
				} else {
					Logger.warn("Parsing failed");
				}
			}

		}
		Logger.info("Playlist size: " + playList.size());
		return playList;
	}

	public static YdlVideo getPlaylistEntry(JSONObject jsonObject) {
		if (jsonObject == null) {
			return null;
		}
		List<YdlFormat> formatList = new ArrayList<>();
		JSONArray formats = (JSONArray) jsonObject.get("formats");
		if (formats != null) {
			for (Object o : formats) {
				Logger.info("Parsing format info");
				JSONObject formatObj = (JSONObject) o;
				String protocol = getString(formatObj.get("protocol"));
				YdlFormat format = new YdlFormat();
				format.protocol = protocol;
				format.url = getString(formatObj.get("url"));
				format.audioCodec = getString(formatObj.get("acodec"));
				format.videoCodec = getString(formatObj.get("vcodec"));
				format.width = getInt(formatObj.get("width"));
				format.height = getInt(formatObj.get("height"));
				format.extension = getString(formatObj.get("ext"));
				if ("mpd".equalsIgnoreCase(format.extension)) {
					continue;
				}
				format.formatNote = getString(formatObj.get("format_note"));
				format.format = getString(formatObj.get("format"));
				String sabr = formatObj.get("abr") + "";
				try {
					format.abr = Integer.parseInt(sabr);
				} catch (NumberFormatException e) {
					format.abr = -1;
					Logger.error(e);
				}

				JSONObject jsHeaders = (JSONObject)formatObj.get("http_headers");
				if (jsHeaders != null) {
					format.headers = new ArrayList<>();
					for (Object item : jsHeaders.keySet()) {
						String key = (String) item;
						String value = (String) jsHeaders.get(key);
						format.headers.add(new HttpHeader(key, value));
					}
				}
				if (protocol.equals("http_dash_segments")) {
					String baseUrl = (String) formatObj.get("fragment_base_url");
					JSONArray fragmentArr = (JSONArray) formatObj.get("fragments");
					String[] fragments = new String[fragmentArr.size()];
					for (int j = 0; j < fragmentArr.size(); j++) {
						JSONObject frag = (JSONObject) fragmentArr.get(j);
						String url = (String) frag.get("url");
						fragments[j] = url != null ? url : baseUrl + frag.get("path");
					}
					format.fragments = fragments;
				}
				formatList.add(format);
			}
		} else {
			String url = getString(jsonObject.get("url"));
			if (url != null) {
				YdlFormat format = new YdlFormat();
				format.protocol = getString(jsonObject.get("protocol"));
				format.url = url;
				format.audioCodec = getString(jsonObject.get("acodec"));
				format.videoCodec = getString(jsonObject.get("vcodec"));
				try {
					format.width = getInt(jsonObject.get("width"));
				} catch (NumberFormatException e) {
					format.width = -1;
					Logger.error(e);
				}
				try {
					format.height = getInt(jsonObject.get("height"));
				} catch (NumberFormatException e) {
					format.width = -1;
					Logger.error(e);
				}

				format.extension = getString(jsonObject.get("ext"));
				format.formatNote = getString(jsonObject.get("format_note"));
				format.format = getString(jsonObject.get("format"));
				String sabr = jsonObject.get("abr") + "";
				try {
					format.abr = Integer.parseInt(sabr);
				} catch (NumberFormatException e) {
					format.abr = -1;
					Logger.error(e);
				}
				formatList.add(format);
			}
		}

		Logger.info("Format list count: " + formatList.size());
		ArrayList<YdlMediaFormat> mediaList = new ArrayList<>();

		for (int i = 0; i < formatList.size(); i++) {
			YdlFormat fmt = formatList.get(i);
			if (fmt.protocol.equals("http_dash_segments")) {
				continue;
			}
			int type = getVideoType(fmt);
			if (type == DASH_VIDEO_ONLY) {
				for (YdlFormat fmt2 : formatList) {
					int type2 = getVideoType(fmt2);
					if (type2 == DASH_AUDIO_ONLY) {
						YdlMediaFormat media = new YdlMediaFormat();
						media.type = DASH_HTTP;
						if (fmt.protocol.equals(fmt2.protocol)) {
							media.audioSegments = new String[1];
							media.audioSegments[0] = fmt2.url;
							media.abr = fmt2.abr;
							media.videoSegments = new String[1];
							media.videoSegments[0] = fmt.url;
							if (fmt.headers != null) {
								media.headers.addAll(fmt.headers);
							}
							if (fmt2.headers != null) {
								media.headers2.addAll(fmt2.headers);
							}

							if (((fmt.extension + "").equals(fmt2.extension + ""))
									|| ((fmt.extension + "").equals("mp4") && (fmt2.extension + "").equals("m4a"))) {
								media.extension = fmt.extension;
							} else {
								media.extension = "mkv";
							}
							media.width = fmt.width;
							media.height = fmt.height;
							media.format = createFormat(media.extension, fmt.format, fmt2.format, fmt2.audioCodec,
									fmt.videoCodec, fmt.width, fmt.height, fmt2.abr);
							Logger.info(media.format + " " + media.url);
							checkAndAddMedia(media, mediaList);
						}
					}
				}
			} else if (type != DASH_AUDIO_ONLY) {
				YdlMediaFormat media = new YdlMediaFormat();
				if ("m3u8".equals(fmt.protocol) || "m3u8_native".equals(fmt.protocol)) {
					media.type = HLS;
				} else if ("f4m".equals(fmt.protocol)) {
					media.type = HDS;
				} else if ("http".equals(fmt.protocol) || "https".equals(fmt.protocol)) {
					media.type = HTTP;
				} else {
					Logger.warn("unsupported protocol: " + fmt.protocol);
					continue;
				}
				media.url = fmt.url;
				media.extension = fmt.extension;
				media.width = fmt.width;
				media.height = fmt.height;
				media.format = createFormat(media.extension, fmt.format, null, fmt.audioCodec, fmt.videoCodec,
						fmt.width, fmt.height, -1);
				Logger.info(media.format + " " + media.url);
				if (fmt.headers != null) {
					media.headers.addAll(fmt.headers);
				}
				checkAndAddMedia(media, mediaList);
			}
		}
		Logger.info("VIDEO----" + jsonObject.get("title"));
		for (YdlMediaFormat ydlMediaFormat : mediaList) {
			Logger.info(ydlMediaFormat.type + " " + ydlMediaFormat.format);
		}

		YdlVideo pl = new YdlVideo();
		pl.mediaFormats.addAll(mediaList);
		pl.mediaFormats.sort((o1, o2) -> {
			if (o1.width > o2.width) {
				return -1;
			} else if (o1.width < o2.width) {
				return 1;
			} else {
				return Integer.compare(o2.abr, o1.abr);
			}
		});
		String stitle = (String) jsonObject.get("title");
		if (!StringUtils.isNullOrEmptyOrBlank(stitle)) {
			pl.title = stitle;
		}

		String thumbnail = (String) jsonObject.get("thumbnail");
		if (thumbnail != null) {
			if (!(thumbnail.equals("none") || thumbnail.equals("null"))) {
				pl.thumbnail = thumbnail;
			}
		}

		if (pl.thumbnail == null) {
			JSONArray thumbnails = (JSONArray) jsonObject.get("thumbnails");
			if (thumbnails != null) {
				for (Object o : thumbnails) {
					Logger.info("Parsing thumbnails info");
					JSONObject thumbnailObj = (JSONObject) o;
					thumbnail = (String) thumbnailObj.get("url");
					if (thumbnail != null) {
						if (!(thumbnail.equals("none") || thumbnail.equals("null"))) {
							pl.thumbnail = thumbnail;
							break;
						}
					}
				}
			}
		}

		String sdur = (jsonObject.get("duration") + "");
		if (!(sdur.equals("none") || sdur.equals("null"))) {
			try {
				pl.duration = Long.parseLong(sdur);
			} catch (NumberFormatException e) {
				pl.duration = -1;
				Logger.error(e);
			}
		}
		return pl;
	}

	private static void checkAndAddMedia(YdlMediaFormat fmt, ArrayList<YdlMediaFormat> mediaList) {
		for (YdlMediaFormat m : mediaList) {
			if (fmt.type == m.type) {
				if (fmt.type == DASH_HTTP) {
					boolean sameAudio = false;
					boolean sameVideo = false;
					if (fmt.audioSegments == null) {
						if (m.audioSegments == null) {
							sameAudio = true;
						}
					} else {
						if (m.audioSegments != null) {
							if (fmt.audioSegments.length == m.audioSegments.length) {
								sameAudio = true;
								for (int j = 0; j < fmt.audioSegments.length; j++) {
									if (!fmt.audioSegments[j].equals(m.audioSegments[j])) {
										sameAudio = false;
										break;
									}
								}
							}
						}
					}
					if (fmt.videoSegments == null) {
						if (m.videoSegments == null) {
							sameVideo = true;
						}
					} else {
						if (m.videoSegments != null) {
							if (fmt.videoSegments.length == m.videoSegments.length) {
								sameVideo = true;
								for (int j = 0; j < fmt.videoSegments.length; j++) {
									if (!fmt.videoSegments[j].equals(m.videoSegments[j])) {
										sameVideo = false;
										break;
									}
								}
							}
						}
					}
					if (sameAudio && sameVideo) {
						return;
					}
				} else {
					if (m.url.equals(fmt.url)) {
						return;
					}
				}
			}

		}

		mediaList.add(fmt);
	}

	private static int getVideoType(YdlFormat fmt) {

		String fmtNote = null;
		String acodec = null;
		String vcodec = null;
		if (fmt.formatNote != null) {
			fmtNote = fmt.formatNote.toLowerCase();
			if (fmtNote.equals("none") || fmtNote.length() < 1) {
				fmtNote = null;
			}
		}
		if (fmtNote == null) {
			fmtNote = "";
		}
		if (fmt.audioCodec != null) {
			acodec = fmt.audioCodec.toLowerCase();
			if (acodec.equals("none") || acodec.length() < 1) {
				acodec = null;
			}
		}
		if (fmt.videoCodec != null) {
			vcodec = fmt.videoCodec.toLowerCase();
			if (vcodec.equals("none") || vcodec.length() < 1) {
				vcodec = null;
			}
		}

		if (fmtNote.contains("dash audio")) {
			return DASH_AUDIO_ONLY;
		}
		if (fmtNote.contains("dash video")) {
			return DASH_VIDEO_ONLY;
		}
		if (acodec == null && vcodec == null) {
			return -1;
		}
		if (acodec != null && vcodec != null) {
			return -1;
		}
		if (acodec != null) {
			return DASH_AUDIO_ONLY;
		}
		return DASH_VIDEO_ONLY;
	}

	private static int getInt(Object obj) {
		if (obj == null) {
			return -1;
		}
		if (obj.toString().contains("none"))
			return -1;
		return Integer.parseInt(obj + "");
	}

	private static String getString(Object obj) {
		return (String) obj;
	}

	public static String nvl(String str) {
		if (str == null)
			return "";
		return str;
	}

	public static String createFormat(String ext, String fmt1, String fmt2, String audioCodec, String videoCodec,
			int width, int height, int abr) {
		StringBuilder sb = new StringBuilder();
		ext = nvl(ext);
		if (ext.length() > 0) {
			sb.append(ext.toUpperCase());
		}

		if (height > 0) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(height).append("p");
		}

		if (abr > 0) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(abr).append("k");
		}

		audioCodec = nvl(audioCodec);
		if (audioCodec.contains("none")) {
			audioCodec = "";
		}

		videoCodec = nvl(videoCodec);
		if (videoCodec.contains("none")) {
			videoCodec = "";
		}

		if (audioCodec.length() > 0) {
			if (sb.length() > 0)
				sb.append(" ");
			sb.append(FormatUtilities.getFriendlyCodec(audioCodec));
		}

		if (videoCodec.length() > 0) {
			if (sb.length() > 0) {
				if (audioCodec.length() > 0) {
					sb.append("/");
				} else {
					sb.append(" ");
				}
			}
			sb.append(FormatUtilities.getFriendlyCodec(videoCodec));
		}

		return sb.toString();
	}

	public static class YdlVideo {
		public String title;
		public ArrayList<YdlMediaFormat> mediaFormats = new ArrayList<>();
		public int index;
		public String thumbnail;
		public long duration;
	}

	public static class YdlMediaFormat {
		public int type;
		public String url;
		public String[] audioSegments, videoSegments;
		public String format;
		public String extension;
		public ArrayList<HttpHeader> headers = new ArrayList<>();
		public ArrayList<HttpHeader> headers2 = new ArrayList<>();
		public int width, height;
		public int abr;

		@Override
		public String toString() {
			return format;
		}
	}

	static class YdlFormat {
		String url;
		String format;
		String[] fragments;
		String formatNote;
		int width, height;
		String protocol;
		String extension;
		String audioCodec;
		String videoCodec;
		int abr;
		ArrayList<HttpHeader> headers;
	}
}