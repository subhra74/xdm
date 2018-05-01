package xdman.monitoring;

import java.util.*;

import xdman.network.http.HeaderCollection;
import xdman.util.Logger;

public class YtUtil {
	static DASH_INFO lastVid;

	public static boolean isNormalVideo(int itag) {
		return ((itag > 4 && itag < 79) || (itag > 81 && itag < 86) || (itag > 99 && itag < 103));
	}

	static Object lockObject = new Object();

	static ArrayList<DASH_INFO> videoQueue = new ArrayList<DASH_INFO>(), audioQueue = new ArrayList<DASH_INFO>();

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
				Logger.log("video added " + videoQueue.size());

				return true;
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
				Logger.log("added added " + audioQueue.size());
				return true;
			}
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
						Logger.log("found matching audio");
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
						Logger.log("found matching video");
						return di;
					}
				}
			}
			return null;
		}
	}

	private static HashMap<Integer, String> itags;
	
	static {
		itags = new HashMap<Integer, String>();
		itags.put(5, "240p");
		itags.put(6, "270p");
		itags.put(13, "Small");
		itags.put(17, "144p");
		itags.put(18, "360p");
		itags.put(22, "720p");
		itags.put(34, "360p");
		itags.put(35, "480p");
		itags.put(36, "240p");
		itags.put(37, "1080p");
		itags.put(38, "1080p");
		itags.put(43, "360p");
		itags.put(44, "480p");
		itags.put(45, "720p");
		itags.put(46, "1080p");
		itags.put(59, "480p");
		itags.put(78, "480p");
		itags.put(82, "360p 3D");
		itags.put(83, "480p 3D");
		itags.put(84, "720p 3D");
		itags.put(85, "1080p 3D");
		itags.put(100, "360p 3D");
		itags.put(101, "480p 3D");
		itags.put(102, "720p 3D");
		itags.put(133, "240p");
		itags.put(134, "360p");
		itags.put(135, "480p");
		itags.put(136, "720p");
		itags.put(137, "1080p");
		itags.put(139, "Low bitrate");
		itags.put(140, "Med bitrate");
		itags.put(141, "Hi  bitrate");
		itags.put(160, "144p");
		itags.put(167, "360p");
		itags.put(168, "480p");
		itags.put(169, "720p");
		itags.put(170, "1080p");
		itags.put(171, "Med bitrate");
		itags.put(172, "Hi  bitrate");
		itags.put(218, "480p");
		itags.put(219, "480p");
		itags.put(242, "240p");
		itags.put(243, "360p");
		itags.put(244, "480p");
		itags.put(245, "480p");
		itags.put(246, "480p");
		itags.put(247, "720p");
		itags.put(248, "1080p");
		itags.put(264, "1440p");
		itags.put(266, "2160p");
		itags.put(271, "1440p");
		itags.put(272, "2160p");
		itags.put(278, "144p");
		itags.put(298, "720p");
		itags.put(302, "720p");
		itags.put(303, "1080p");
		itags.put(308, "1440p");
		itags.put(313, "2160p");
		itags.put(315, "2160p");
		itags.put(299, "2160p");

	}

	public static String getInfoFromITAG(int itag) {
		return (String) itags.get(itag);
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
