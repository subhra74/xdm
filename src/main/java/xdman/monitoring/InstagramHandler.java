package xdman.monitoring;

import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.BufferedReader;
import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InstagramHandler {
	private static Pattern pattern;

	public static boolean handle(File instagramFile, ParsedHookData data) {
		if (!instagramFile.exists()) {
			Logger.log("No saved Instagram",
					instagramFile.getAbsolutePath());
			return false;
		}
		BufferedReader bufferedReader = null;
		try {
			StringBuffer buf = new StringBuffer();
			Logger.log("Loading Instagram...",
					instagramFile.getAbsolutePath());
			bufferedReader = XDMUtils.getBufferedReader(instagramFile);
			String ln;
			while ((ln = bufferedReader.readLine()) != null) {
				buf.append(ln + "\n");
			}
			Logger.log("Parsing instagram page...");
			if (pattern == null) {
				pattern = Pattern.compile("\"video\\_url\"\\s*:\\s*\"(.*?)\"");
			}
			Matcher matcher = pattern.matcher(buf);
			if (matcher.find()) {
				int start = matcher.start();
				int end = matcher.end();
				String url = matcher.group(1);
				Logger.log("instagram Url:", url);
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(url);
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				String ext = XDMUtils.getExtension(XDMUtils.getFileName(url));
				if (ext != null) {
					ext = ext.replace(".", "").toUpperCase();
				} else {
					ext = "";
				}
				String instagramMediaFilePath = String.format("%s.%s", file, ext);
				Logger.log("Instagram",
						metadata,
						"Media File Path:",
						instagramMediaFilePath);
				XDMApp.getInstance().addMedia(metadata, instagramMediaFilePath, ext);
			}
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception e2) {
					Logger.log(e2);
				}
			}
		}
	}
}
