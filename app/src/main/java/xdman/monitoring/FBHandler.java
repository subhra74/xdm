package xdman.monitoring;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

import xdman.XDMApp;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class FBHandler {
//	public static void main(String[] args) {
//		handle(new File("C:\\Users\\dasgupts\\Downloads\\LitestandTailLoadPagelet"), null);
//	}

	public static boolean handle(File tempFile, ParsedHookData data) {
		try {
			StringBuffer buf = new StringBuffer();
			InputStream in = new FileInputStream(tempFile);
			BufferedReader r = new BufferedReader(new InputStreamReader(in));
			while (true) {
				String ln = r.readLine();
				if (ln == null) {
					break;
				}
				buf.append(ln + "\n");
			}
			in.close();
			Logger.log("Parsing facebook page...");
			ArrayList<String> sdUrls1 = findURL("sd_src", buf);
			ArrayList<String> sdUrls2 = findURL("sd_src_no_ratelimit", buf);
			ArrayList<String> hdUrls1 = findURL("hd_src", buf);
			ArrayList<String> hdUrls2 = findURL("hd_src_no_ratelimit", buf);
			for (int i = 0; i < sdUrls1.size(); i++) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(sdUrls1.get(i));
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 LOW");
			}
			for (int i = 0; i < sdUrls2.size(); i++) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(sdUrls2.get(i));
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 MEDIUM");
			}
			for (int i = 0; i < hdUrls1.size(); i++) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(hdUrls1.get(i));
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 HD");
			}
			for (int i = 0; i < hdUrls2.size(); i++) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(hdUrls2.get(i));
				metadata.setHeaders(data.getRequestHeaders());
				String file = data.getFile();
				if (StringUtils.isNullOrEmptyOrBlank(file)) {
					file = XDMUtils.getFileName(data.getUrl());
				}
				XDMApp.getInstance().addMedia(metadata, file + ".mp4", "MP4 HQ");
			}
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}
	}

	private static ArrayList<String> findURL(String keyword, StringBuffer buf) {
		//int index1 = 0;
		int index = 0;
		ArrayList<String> urlList = new ArrayList<String>();
		//String urlStart = ":";// "\"https";
		while (true) {
			index = buf.indexOf(keyword, index);
			if (index < 0)
				break;
			index += keyword.length();
			index = buf.indexOf(":", index);
			if (index < 0) {
				break;
			}
			index += 1;
			//int collonIndex = index;

			while (true) {
				char ch = buf.charAt(index);
				if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t')
					index++;
				else
					break;
			}

			char ch = buf.charAt(index);
			if (ch == '"') {
				index++;
				int index3 = buf.indexOf("\"", index);
				String url = decodeJSONEscape(buf.substring(index, index3).trim().replace("\"", ""));
				Logger.log(keyword + ": " + url);
				urlList.add(url);
			}

			// if (buf.indexOf("null", index) == index) {
			// index += 5;
			// } else
			//
			// while (true) {
			// char ch = buf.charAt(index);
			// if (ch == ',' || ch == '}' || ch == ']')
			// break;
			// else
			// index++;
			// }
			//
			// String url = decodeJSONEscape(buf.substring(collonIndex,
			// index).trim().replace("\"", ""));
			// Logger.log(keyword + ": " + url);
			// if (!url.equals("null")) {
			// urlList.add(url);
			// }

			// int idx=buf.indexOf("null", fromIndex)
			//
			// index1 = buf.indexOf(keyword, index1);// ("\"sd_src\"");
			// if (index1 < 0)
			// break;
			// index1 += keyword.length();
			// int index2 = buf.indexOf(urlStart, index1);
			// if (index2 > 0) {
			// int index3 = buf.indexOf("\"", index2 + urlStart.length());
			// int end = buf.indexOf("\"", index3 + 1);
			// String url = decodeJSONEscape(buf.substring(index3 + 1, end));
			// Logger.log(keyword + ": " + url);
			// if (!url.equals("null")) {
			// urlList.add(url);
			// }
			// }
		}
		return urlList;
	}

	private static String decodeJSONEscape(String json) {
		StringBuffer buf = new StringBuffer();
		int pos = 0;
		while (true) {
			int index = json.indexOf("\\u", pos);
			if (index < 0) {
				if (pos < json.length()) {
					buf.append(json.substring(pos));
				}
				break;
			}
			buf.append(json.substring(pos, index));
			pos = index;
			String code = json.substring(pos + 2, pos + 2 + 4);
			int char_code = Integer.parseInt(code, 16);
			buf.append((char) char_code);
			pos += 6;
		}
		return buf.toString().replace("\\", "");
	}
}
