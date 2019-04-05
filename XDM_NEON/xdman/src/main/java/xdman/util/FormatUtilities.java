package xdman.util;

import java.text.*;
import java.util.*;

import xdman.*;
import xdman.ui.res.StringResource;

public class FormatUtilities {
	private static SimpleDateFormat _format;
	private static final int MB = 1024 * 1024, KB = 1024;

	public static synchronized String formatDate(long date) {
		if (_format == null) {
			_format = new SimpleDateFormat("yyyy-MM-dd");
		}
		Date dt = new Date(date);
		return _format.format(dt);
	}

	public static String formatSize(double length) {
		if (length < 0)
			return "---";
		if (length > MB) {
			return String.format("%.1f MB", (float) length / MB);
		} else if (length > KB) {
			return String.format("%.1f KB", (float) length / KB);
		} else {
			return String.format("%d B", (int) length);
		}
	}

	public static String getFormattedStatus(DownloadEntry ent) {
		String statStr = "";
		if (ent.getQueueId() != null) {
			DownloadQueue q = QueueManager.getInstance().getQueueById(ent.getQueueId());
			String qname = "";
			if (q != null && q.getQueueId() != null) {
				qname = q.getQueueId().length() > 0 ? "[ " + q.getName() + " ] " : "";
			}
			statStr += qname;
		}

		if (ent.getState() == XDMConstants.FINISHED) {
			statStr += StringResource.get("STAT_FINISHED");
		} else if (ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED) {
			statStr += StringResource.get("STAT_PAUSED");
		} else if (ent.getState() == XDMConstants.ASSEMBLING) {
			statStr += StringResource.get("STAT_ASSEMBLING");
		} else {
			statStr += StringResource.get("STAT_DOWNLOADING");
		}
		String sizeStr = formatSize(ent.getSize());
		if (ent.getState() == XDMConstants.FINISHED) {
			return statStr + " " + sizeStr;
		} else {
			if (ent.getSize() > 0) {
				String downloadedStr = formatSize(ent.getDownloaded());
				String progressStr = ent.getProgress() + "%";
				return statStr + " " + progressStr + " [ " + downloadedStr + " / " + sizeStr + " ]";
			} else {
				return statStr + (ent.getProgress() > 0 ? (" " + ent.getProgress() + "%") : "")
						+ (ent.getDownloaded() > 0 ? " " + formatSize(ent.getDownloaded())
								: (ent.getState() == XDMConstants.PAUSED || ent.getState() == XDMConstants.FAILED ? ""
										: " ..."));
			}
		}
	}

	public static String getETA(double length, float rate) {
		if (length == 0)
			return "00:00:00";
		if (length < 1 || rate <= 0)
			return "---";
		int sec = (int) (length / rate);
		return hms(sec);
	}

	public static String hms(int sec) {
		int hrs = 0, min = 0;
		hrs = sec / 3600;
		min = (sec % 3600) / 60;
		sec = sec % 60;
		String str = String.format("%02d:%02d:%02d", hrs, min, sec);
		return str;
	}

	public static String getResolution(String res) {
		if (res != null) {
			res = res.toLowerCase().trim();
			int index = res.indexOf("x");
			if (index > 0) {
				res = res.substring(index + 1).trim();
				try {
					Integer.parseInt(res);
					return res + "p";
				} catch (Exception e) {
				}
			}
		}
		return res;
	}

	public static String getFriendlyCodec(String name) {
		if (!StringUtils.isNullOrEmptyOrBlank(name)) {
			name = name.toLowerCase().trim();
			if (name.startsWith("avc")) {
				return "h264";
			}
			if (name.startsWith("mp4a")) {
				return "aac";
			}
			if (name.startsWith("mp4v")) {
				return "mpeg4";
			}
			if (name.startsWith("samr")) {
				return "amr";
			}
		}
		return name;
	}
}
