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

package xdman.monitoring;

import java.util.ArrayList;
import java.util.HashMap;

import org.tinylog.Logger;

import xdman.network.http.HeaderCollection;

public class YtUtil {

	static DASH_INFO lastVid;

	public static boolean isNormalVideo(int infoTag) {
		return ((infoTag > 4 && infoTag < 79) || (infoTag > 81 && infoTag < 86) || (infoTag > 99 && infoTag < 103));
	}

	static final Object lockObject = new Object();

	static ArrayList<DASH_INFO> videoQueue = new ArrayList<>(), audioQueue = new ArrayList<>();

	public static boolean addToQueue(DASH_INFO info) {
		synchronized (lockObject) {
			if (videoQueue.size() > 32) {
				videoQueue.remove(0);
			}
			if (audioQueue.size() > 32) {
				audioQueue.remove(0);
			}
			if (info.video) {
				for (int i = videoQueue.size() - 1; i >= 0; i--) {
					DASH_INFO di = videoQueue.get(i);
					if (di.clen == info.clen) {
						if (di.id.equals(info.id)) {
							return false;
						}
					}
				}
				videoQueue.add(info);
				Logger.info("video added " + videoQueue.size());

			} else {
				for (int i = audioQueue.size() - 1; i >= 0; i--) {
					DASH_INFO di = audioQueue.get(i);
					if (di.clen == info.clen) {
						if (di.id.equals(info.id)) {
							return false;
						}
					}
				}
				audioQueue.add(info);
				Logger.info("added added " + audioQueue.size());
			}
			return true;
		}
	}

	public static DASH_INFO getDASHPair(DASH_INFO info) {
		synchronized (lockObject) {
			if (info.video) {
				if (audioQueue.size() < 1)
					return null;
				for (int i = audioQueue.size() - 1; i >= 0; i--) {
					DASH_INFO di = audioQueue.get(i);
					if (di.id.equals(info.id)) {
						Logger.info("found matching audio");
						return di;
					}
				}
			} else {
				if (videoQueue.size() < 1)
					return null;
				for (int i = videoQueue.size() - 1; i >= 0; i--) {
					DASH_INFO di = videoQueue.get(i);
					if (di.id.equals(info.id)) {
						if ((lastVid != null) && (lastVid.clen == di.clen)) {
							return null;
						}
						lastVid = di;
						Logger.info("found matching video");
						return di;
					}
				}
			}
			return null;
		}
	}

	private static final HashMap<Integer, String> INFORMATION_TAGS;

	static {
		INFORMATION_TAGS = new HashMap<>();
		INFORMATION_TAGS.put(5, "240p");
		INFORMATION_TAGS.put(6, "270p");
		INFORMATION_TAGS.put(13, "Small");
		INFORMATION_TAGS.put(17, "144p");
		INFORMATION_TAGS.put(18, "360p");
		INFORMATION_TAGS.put(22, "720p");
		INFORMATION_TAGS.put(34, "360p");
		INFORMATION_TAGS.put(35, "480p");
		INFORMATION_TAGS.put(36, "240p");
		INFORMATION_TAGS.put(37, "1080p");
		INFORMATION_TAGS.put(38, "1080p");
		INFORMATION_TAGS.put(43, "360p");
		INFORMATION_TAGS.put(44, "480p");
		INFORMATION_TAGS.put(45, "720p");
		INFORMATION_TAGS.put(46, "1080p");
		INFORMATION_TAGS.put(59, "480p");
		INFORMATION_TAGS.put(78, "480p");
		INFORMATION_TAGS.put(82, "360p 3D");
		INFORMATION_TAGS.put(83, "480p 3D");
		INFORMATION_TAGS.put(84, "720p 3D");
		INFORMATION_TAGS.put(85, "1080p 3D");
		INFORMATION_TAGS.put(100, "360p 3D");
		INFORMATION_TAGS.put(101, "480p 3D");
		INFORMATION_TAGS.put(102, "720p 3D");
		INFORMATION_TAGS.put(133, "240p");
		INFORMATION_TAGS.put(134, "360p");
		INFORMATION_TAGS.put(135, "480p");
		INFORMATION_TAGS.put(136, "720p");
		INFORMATION_TAGS.put(137, "1080p");
		INFORMATION_TAGS.put(139, "Low bitrate");
		INFORMATION_TAGS.put(140, "Med bitrate");
		INFORMATION_TAGS.put(141, "Hi  bitrate");
		INFORMATION_TAGS.put(160, "144p");
		INFORMATION_TAGS.put(167, "360p");
		INFORMATION_TAGS.put(168, "480p");
		INFORMATION_TAGS.put(169, "720p");
		INFORMATION_TAGS.put(170, "1080p");
		INFORMATION_TAGS.put(171, "Med bitrate");
		INFORMATION_TAGS.put(172, "Hi  bitrate");
		INFORMATION_TAGS.put(218, "480p");
		INFORMATION_TAGS.put(219, "480p");
		INFORMATION_TAGS.put(242, "240p");
		INFORMATION_TAGS.put(243, "360p");
		INFORMATION_TAGS.put(244, "480p");
		INFORMATION_TAGS.put(245, "480p");
		INFORMATION_TAGS.put(246, "480p");
		INFORMATION_TAGS.put(247, "720p");
		INFORMATION_TAGS.put(248, "1080p");
		INFORMATION_TAGS.put(264, "1440p");
		INFORMATION_TAGS.put(266, "2160p");
		INFORMATION_TAGS.put(271, "1440p");
		INFORMATION_TAGS.put(272, "2160p");
		INFORMATION_TAGS.put(278, "144p");
		INFORMATION_TAGS.put(298, "720p");
		INFORMATION_TAGS.put(302, "720p");
		INFORMATION_TAGS.put(303, "1080p");
		INFORMATION_TAGS.put(308, "1440p");
		INFORMATION_TAGS.put(313, "2160p");
		INFORMATION_TAGS.put(315, "2160p");
		INFORMATION_TAGS.put(299, "2160p");

	}

	public static String getInfoFromITAG(int infoTag) {
		return INFORMATION_TAGS.get(infoTag);
	}

}

class DASH_INFO {
	public String url;
	public long clen;
	public boolean video;
	public String id;
	public int itag;
	public String mime;
	public HeaderCollection headers;
}
