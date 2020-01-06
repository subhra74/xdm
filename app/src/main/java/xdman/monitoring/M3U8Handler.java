package xdman.monitoring;

import java.io.File;
import java.util.List;

import xdman.XDMApp;
import xdman.downloaders.hls.HlsPlaylist;
import xdman.downloaders.hls.HlsPlaylistItem;
import xdman.downloaders.hls.PlaylistParser;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class M3U8Handler {
	public static boolean handle(File m3u8file, ParsedHookData data) {
		try {
			HlsPlaylist playlist = PlaylistParser.parse(m3u8file.getAbsolutePath(), data.getUrl());
			if (playlist == null)
				return true;
			// M3U8Manifest manifest = new M3U8Manifest(m3u8file.getAbsolutePath(),
			// data.getUrl());
			// if (manifest.isEncrypted()) {
			// return true;
			// }
			if (!playlist.isMaster()) {
				if (playlist.getItems() != null && playlist.getItems().size() > 0) {
					HlsMetadata metadata = new HlsMetadata();
					metadata.setUrl(data.getUrl());
					metadata.setHeaders(data.getRequestHeaders());
					String file = data.getFile();
					if (StringUtils.isNullOrEmptyOrBlank(file)) {
						file = XDMUtils.getFileName(data.getUrl());
					}
					XDMApp.getInstance().addMedia(metadata, file + ".ts", "HLS");
				}
			} else {
				List<HlsPlaylistItem> items = playlist.getItems();
				// ArrayList<String> urls = manifest.getMediaUrls();
				if (items != null) {
					for (int i = 0; i < items.size(); i++) {
						HlsPlaylistItem item = items.get(i);
						String url = item.getUrl();
						HlsMetadata metadata = new HlsMetadata();
						metadata.setUrl(url);
						metadata.setHeaders(data.getRequestHeaders());
						String file = data.getFile();
						if (StringUtils.isNullOrEmptyOrBlank(file)) {
							file = XDMUtils.getFileName(data.getUrl());
						}
						StringBuilder infoStr = new StringBuilder();
						if (!StringUtils.isNullOrEmptyOrBlank(item.getBandwidth())) {
							infoStr.append(item.getBandwidth());
						}
						if (infoStr.length() > 0) {
							infoStr.append(" ");
						}
						if (!StringUtils.isNullOrEmptyOrBlank(item.getResolution())) {
							infoStr.append(item.getResolution());
						}
						XDMApp.getInstance().addMedia(metadata, file + ".ts", infoStr.toString());
					}
				}
			}
			return true;
		} catch (Exception e) {
		}
		return false;
	}
}
