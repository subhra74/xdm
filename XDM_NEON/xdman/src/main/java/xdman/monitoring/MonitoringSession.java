package xdman.monitoring;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import xdman.Config;
import xdman.XDMApp;
import xdman.XDMConstants;
import xdman.downloaders.metadata.DashMetadata;
import xdman.downloaders.metadata.HlsMetadata;
import xdman.downloaders.metadata.HttpMetadata;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpHeader;
import xdman.network.http.JavaHttpClient;
import xdman.preview.FFmpegStream;
import xdman.preview.PreviewStream;
import xdman.ui.components.VideoPopupItem;
import xdman.util.FormatUtilities;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

public class MonitoringSession implements Runnable {
	// private String msg204 = "HTTP/1.1 204 No Content\r\n" + "Content-length:
	// 0\r\n\r\n";

	private Socket sock;
	private InputStream inStream;
	private OutputStream outStream;
	private Request request;
	private Response response;

	public MonitoringSession(Socket socket) {
		this.sock = socket;
		this.request = new Request();
		this.response = new Response();
		System.out.println("New session");
	}

	public void start() {
		Thread t = new Thread(this);
		t.setDaemon(true);
		t.start();
	}

	private void setResponseOk(Response res) {
		res.setCode(200);
		res.setMessage("OK");
		HeaderCollection headers = new HeaderCollection();
		headers.setValue("content-type", "application/json");
		headers.setValue("Cache-Control", "max-age=0, no-cache, must-revalidate");
		res.setHeaders(headers);
	}

	private void onDownload(Request request, Response res) throws UnsupportedEncodingException {
		try {
			Logger.log(new String(request.getBody()));
			byte[] b = request.getBody();
			ParsedHookData data = ParsedHookData.parse(b);
			if (data.getUrl() != null && data.getUrl().length() > 0) {
				HttpMetadata metadata = new HttpMetadata();
				metadata.setUrl(data.getUrl());
				metadata.setHeaders(data.getRequestHeaders());
				metadata.setSize(data.getContentLength());
				String file = data.getFile();
				XDMApp.getInstance().addDownload(metadata, file);
			}
		} finally {
			setResponseOk(res);
		}
	}

	private void onVideoRetrieve(Request request, Response res) throws UnsupportedEncodingException {
		try {
			String id = new String(request.getBody(), "utf-8");
			for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
				if (id.equals(item.getMetadata().getId())) {
					HttpMetadata md = item.getMetadata().derive();
					Logger.log("dash metdata ? " + (md instanceof DashMetadata));
					XDMApp.getInstance().addVideo(md, item.getFile());
				}
			}
		} finally {
			setResponseOk(res);
		}
	}

	private void onLinksReceived(Request request, Response res) throws UnsupportedEncodingException {
		try {
			Logger.log(new String(request.getBody()));
			byte[] b = request.getBody();
			List<ParsedHookData> list = ParsedHookData.parseLinks(b);
			List<HttpMetadata> metadatas = new ArrayList<>();
			for (ParsedHookData d : list) {
				System.out.println(d);
				HttpMetadata md = new HttpMetadata();
				md.setUrl(d.getUrl());
				md.setHeaders(d.getRequestHeaders());
				metadatas.add(md);
			}
			XDMApp.getInstance().addLinks(metadatas);
		} finally {
			setResponseOk(res);
		}
	}

	private void on204(Request request, Response res) {
		res.setCode(204);
		res.setMessage("No Content");
		HeaderCollection headers = new HeaderCollection();
		headers.setValue("Cache-Control", "max-age=0, no-cache, must-revalidate");
		res.setHeaders(headers);
	}

	private void onVideo(Request request, Response res) throws UnsupportedEncodingException {
		try {
			Logger.log(new String(request.getBody()));
			if (!Config.getInstance().isShowVideoNotification()) {
				return;
			}
			byte[] b = request.getBody();
			ParsedHookData data = ParsedHookData.parse(b);
			String type = data.getContentType();
			if (type == null) {
				type = "";
			}
			if (type.contains("f4f") || type.contains("m4s") || type.contains("mp2t") || data.getUrl().contains("fcs")
					|| data.getUrl().contains("abst") || data.getUrl().contains("f4x")
					|| data.getUrl().contains(".fbcdn") || data.getUrl().contains("http://127.0.0.1:9614")) {
				return;
			}
			if (!(processDashSegment(data) || processVideoManifest(data))) {
				processNormalVideo(data);
			}
		} finally {
			setResponseOk(res);
		}
	}

	private void onQuit(Request request, Response res) {
		XDMApp.getInstance().exit();
	}

	private void onCmd(Request request, Response res) {
		byte[] data = request.getBody();
		if (data == null || data.length < 1) {
			XDMApp.getInstance().showMainWindow();
		} else {
			String[] arr = new String(data).split("\n");
			for (int i = 0; i < arr.length; i++) {
				String str = arr[i];
				int index = str.indexOf(":");
				if (index < 1)
					continue;
				String key = str.substring(0, index).trim();
				String val = str.substring(index + 1).trim();
				if (key.equals("url")) {
					String url = val;
					HttpMetadata metadata = new HttpMetadata();
					metadata.setUrl(url);
					String file = XDMUtils.getFileName(url);
					XDMApp.getInstance().addDownload(metadata, file);
				}
			}
		}
		setResponseOk(res);
	}

	private String encode(String str) {
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (char ch : str.toCharArray()) {
			if (count > 0)
				sb.append(",");
			sb.append((int) ch);
			count++;
		}
		return sb.toString();
	}

	private void onSync(Request request, Response res) throws UnsupportedEncodingException {
		StringBuffer json = new StringBuffer();
		json.append("{\n\"enabled\": ");
		json.append(Config.getInstance().isBrowserMonitoringEnabled());
		json.append(",\n\"blockedHosts\": [");
		appendArray(Config.getInstance().getBlockedHosts(), json);
		json.append("],");
		json.append("\n\"videoUrls\": [");
		appendArray(Config.getInstance().getVidUrls(), json);
		json.append("],");
		json.append("\n\"fileExts\": [");
		appendArray(Config.getInstance().getFileExts(), json);
		json.append("],");
		json.append("\n\"vidExts\": [");
		appendArray(Config.getInstance().getVidExts(), json);
		json.append("],");
		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (VideoPopupItem item : XDMApp.getInstance().getVideoItemsList()) {
			String id = item.getMetadata().getId();
			String text = encode(item.getFile());
			String info = item.getInfo();
			if (count > 0)
				sb.append(",");
			sb.append(String.format("{\"id\": \"%s\", \"text\": \"%s\",\"info\":\"%s\"}", id, text, info));
			count++;
		}
		json.append("\n\"vidList\": [");
		json.append(sb.toString());
		json.append("],");
		String mimeTypes = "\n\"mimeList\": [\"video/\",\"audio/\",\"mpegurl\",\"f4m\",\"m3u8\"]";
		json.append(mimeTypes);
		json.append("\n}");

		// System.out.println(json);

		byte[] b = json.toString().getBytes();

		res.setCode(200);
		res.setMessage("OK");

		HeaderCollection headers = new HeaderCollection();
		headers.addHeader("Content-Length", b.length + "");
		headers.addHeader("Content-Type", "application/json");
		headers.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
		headers.addHeader("Pragma", "no-cache");
		headers.addHeader("Expires", "0");
		res.setHeaders(headers);
		res.setBody(b);
	}

	private void appendArray(String[] arr, StringBuffer buf) {
		boolean insertComma = false;
		if (arr != null && arr.length > 0) {
			for (int i = 0; i < arr.length; i++) {
				if (insertComma) {
					buf.append(",");
				} else {
					insertComma = true;
				}
				buf.append("\"" + arr[i] + "\"");
			}
		}
	}

	// private void onFF(Request request, Response res) {
	// ByteArrayOutputStream bout=new ByteArrayOutputStream();
	// InputStream inStream = StringResource.class
	// .getResourceAsStream("/addons/" + "xdm_ff_webext.xpi");
	//
	// byte []b=new byte[8192];
	//
	// while(true){
	// int x=inStream.read(b);
	// if(x==-1)break;
	// bout.write(b,0,x);
	// }
	//
	// b=bout.toByteArray();
	//
	// res.setCode(200);
	// res.setMessage("OK");
	//
	// HeaderCollection headers = new HeaderCollection();
	// headers.addHeader("Content-Length", b.length + "");
	// headers.addHeader("Content-Type", "application/json");
	// res.setHeaders(headers);
	// res.setBody(b);
	// }

	private void processRequest(Request request, Response res) throws IOException {
		String verb = request.getUrl();
		if (verb.equals("/sync")) {
			onSync(request, response);
		} else if (verb.equals("/download")) {
			onDownload(request, response);
		} else if (verb.equals("/video")) {
			onVideo(request, response);
		} else if (verb.equals("/cmd")) {
			onCmd(request, response);
		} else if (verb.equals("/quit")) {
			onQuit(request, response);
		} else if (verb.startsWith("/preview")) {
			onPreview(request, response);
		} else if (verb.startsWith("/204")) {
			Logger.log("sending 204...");
			on204(request, response);
		} else if (verb.startsWith("/links")) {
			Logger.log("sending 204...");
			onLinksReceived(request, response);
		} else if (verb.startsWith("/item")) {
			Logger.log("sending 204...");
			onVideoRetrieve(request, response);
		} else if (verb.startsWith("/clear")) {
			Logger.log("sending 204...");
			onVideoClear(request, response);
		} else {
			throw new IOException("invalid verb " + verb);
		}
	}

	private void onVideoClear(Request request, Response response) {
		try {
			XDMApp.getInstance().getVideoItemsList().clear();
		} finally {
			setResponseOk(response);
		}
	}

	// the url should be /preview/ffmpeg/1/{UUID}/{T1,T2,NULL}
	private void onPreview(Request request, Response response) throws IOException {
		PreviewStream ps = null;
		FFmpegStream ff = null;
		try {
			String url = request.getUrl();
			if (url.startsWith("/preview/video")) {

				String[] arr = url.split("/");
				System.out.println(arr.length);
				if (arr.length < 4) {
					return;
				}
				String id = arr[4];
				String tag = null;
				if (arr.length == 6) {
					tag = arr[5];
				}
				int type = Integer.parseInt(arr[3]);
				String resp = "HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\nCache-Control: no-cache, no-store, must-revalidate\r\n"
						+ "Pragma: no-cache\r\n" + "Expires: 0\r\nConnection: close\r\n\r\n";
				outStream.write(resp.getBytes());
				outStream.flush();
				ps = new PreviewStream(id, type, tag);
				byte buf[] = new byte[8192];
				while (true) {
					int x = ps.read(buf);
					if (x == -1) {
						break;
					}

					String slen = Integer.toHexString(x) + "\r\n";
					outStream.write(slen.getBytes());
					outStream.write(buf, 0, x);
					outStream.write("\r\n".getBytes());
					outStream.flush();
				}
				outStream.write("0\r\n\r\n".getBytes());
				System.out.println("Done writing file");
				ps.close();
				outStream.flush();
				outStream.close();
			} else if (url.startsWith("/preview/player")) {
				int index = url.lastIndexOf("/");
				String id = url.substring(index + 1);
				String link = "http://127.0.0.1:9614/preview/media/" + id;

				String html = "<html><body><video id=\"myvideo\" width=\"640\" height=\"480\" controls>\r\n"
						+ "    <source src=\"" + link + "\"" + " type=\"video/webm\"  />\r\n"
						+ "</video><br/><h3>If the video does not play in you browser,<br/>please copy <a href=\""
						+ link + "\">this link</a> and play this in VLC media player</body></html>";

				byte[] b = html.getBytes();

				response.setCode(200);
				response.setMessage("OK");

				HeaderCollection headers = new HeaderCollection();
				headers.addHeader("Content-Length", b.length + "");
				headers.addHeader("Content-Type", "text/html");
				headers.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
				headers.addHeader("Pragma", "no-cache");
				headers.addHeader("Expires", "0");
				response.setHeaders(headers);
				response.setBody(b);
				return;
			} else if (url.startsWith("/preview/media")) {
				boolean dash = false;
				boolean hls = false;
				int index = url.lastIndexOf("/");
				String id = url.substring(index + 1);
				HttpMetadata metadata = HttpMetadata.load(id);
				if (metadata instanceof DashMetadata) {
					dash = true;
				}
				if (metadata instanceof HlsMetadata) {
					hls = true;
				}
				// String html = "";
				// if (dash) {
				// html = "<html><body><video id=\"myvideo\" width=\"640\" height=\"480\"
				// controls>\r\n"
				// + " <source src=\"http://127.0.0.1:9614/preview/video/" + XDMConstants.DASH +
				// "/"
				// + metadata.getId() + "/T1\" />\r\n" + " <audio id=\"myaudio\" controls>\r\n"
				// + " <source src=\"http://127.0.0.1:9614/preview/video/" + XDMConstants.DASH +
				// "/"
				// + metadata.getId() + "/T2\" />\r\n" + " </audio>\r\n" + "</video>\r\n" +
				// "\r\n"
				// + "<script>\r\n" + "var myvideo = document.getElementById(\"myvideo\");\r\n"
				// + "var myaudio = document.getElementById(\"myaudio\");\r\n"
				// + "myvideo.onplay = function() { myaudio.play(); }\r\n"
				// + "myvideo.onpause = function() { myaudio.pause(); }\r\n" +
				// "</script></body></html>";
				//
				// byte[] b = html.getBytes();
				//
				// response.setCode(200);
				// response.setMessage("OK");
				//
				// HeaderCollection headers = new HeaderCollection();
				// headers.addHeader("Content-Length", b.length + "");
				// headers.addHeader("Content-Type", "text/html");
				// response.setHeaders(headers);
				// response.setBody(b);
				// return;
				// } else {
				String input1 = null, input2 = null;
				if (dash) {
					input1 = "http://127.0.0.1:9614/preview/video/" + XDMConstants.DASH + "/" + metadata.getId()
							+ "/T1";
					input2 = "http://127.0.0.1:9614/preview/video/" + XDMConstants.DASH + "/" + metadata.getId()
							+ "/T2";
				} else if (hls) {
					input1 = "http://127.0.0.1:9614/preview/video/" + XDMConstants.HLS + "/" + metadata.getId();
				} else {
					input1 = "http://127.0.0.1:9614/preview/video/" + XDMConstants.HTTP + "/" + metadata.getId();
				}
				// String input = "http://127.0.0.1:9614/preview/video/" + (hls ?
				// XDMConstants.HLS : XDMConstants.HTTP)
				// + "/" + metadata.getId();
				System.out.println("input: " + input1 + " - " + input2);
				String resp = "HTTP/1.1 200 OK\r\nContent-Type: video/webm\r\nTransfer-Encoding: Chunked\r\nCache-Control: no-cache, no-store, must-revalidate\r\n"
						+ "Pragma: no-cache\r\n" + "Expires: 0\r\nConnection: close\r\n\r\n";
				outStream.write(resp.getBytes());
				outStream.flush();
				ff = new FFmpegStream(input1, input2);
				byte buf[] = new byte[8192];
				while (true) {
					int x = ff.read(buf);
					if (x == -1) {
						break;
					}

					String slen = Integer.toHexString(x) + "\r\n";
					outStream.write(slen.getBytes());
					outStream.write(buf, 0, x);
					outStream.write("\r\n".getBytes());
					outStream.flush();
				}
				outStream.write("0\r\n\r\n".getBytes());
				System.out.println("Done writing file");
				ff.close();
				outStream.flush();
				outStream.close();

				System.out.println("Finished writing");

//				html = "<html><body><video id=\"myvideo\" width=\"640\" height=\"480\" controls>\r\n"
//						+ "    <source src=\"http://127.0.0.1:9614/preview/video/"
//						+ (hls ? XDMConstants.HLS : XDMConstants.HTTP) + "/" + metadata.getId() + "\""
//						+ (hls ? "type=\"video/mp2t\"" : "") + "  />\r\n" + "</video></body></html>";
//				// }

			}
		} finally {
			if (ps != null) {
				try {
					ps.close();
				} catch (Exception e) {
				}
			}
			if (ff != null) {
				try {
					System.out.println("Closing FFStream");
					ff.close();
				} catch (Exception e) {
				}
			}
		}
	}

	private void serviceRequest() {
		try {
			inStream = sock.getInputStream();
			outStream = sock.getOutputStream();
			while (true) {
				this.request.read(inStream);
				this.processRequest(this.request, this.response);
				this.response.write(outStream);
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		cleanup();
	}

	private void cleanup() {
		try {
			inStream.close();
		} catch (Exception e) {
		}

		try {
			outStream.close();
		} catch (Exception e) {
		}

		try {
			sock.close();
		} catch (Exception e) {
		}
	}

	@Override
	public void run() {
		serviceRequest();
	}

	private boolean processDashSegment(ParsedHookData data) {
		try {
			URL url = new URL(data.getUrl());
			String host = url.getHost();
			if (!(host.contains("youtube.com") || host.contains("googlevideo.com"))) {
				Logger.log("non yt host");
				return false;
			}
			String type = data.getContentType();
			if (type == null) {
				type = "";
			}
			if (!(type.contains("audio/") || type.contains("video/") || type.contains("application/octet"))) {
				Logger.log("non yt type");
				return false;
			}
			String low_path = data.getUrl().toLowerCase();
			if (low_path.indexOf("videoplayback") >= 0 && low_path.indexOf("itag") >= 0) {
				// found DASH audio/video stream
				if (StringUtils.isNullOrEmptyOrBlank(url.getQuery())) {
					return false;
				}

				int index = data.getUrl().indexOf("?");

				String path = data.getUrl().substring(0, index);
				String query = data.getUrl().substring(index + 1);

				String arr[] = query.split("&");
				StringBuilder yt_url = new StringBuilder();
				yt_url.append(path + "?");
				int itag = 0;
				long clen = 0;
				String id = "";
				String mime = "";

				for (int i = 0; i < arr.length; i++) {
					String str = arr[i];
					index = str.indexOf("=");
					if (index > 0) {
						String key = str.substring(0, index).trim();
						String val = str.substring(index + 1).trim();
						if (key.startsWith("range")) {
							continue;
						}
						if (key.equals("itag")) {
							itag = Integer.parseInt(val);
						}
						if (key.equals("clen")) {
							clen = Integer.parseInt(val);
						}
						if (key.startsWith("mime")) {
							mime = URLDecoder.decode(val, "UTF-8");
						}
						if (str.startsWith("id")) {
							id = val;
						}
					}
					yt_url.append(str);
					if (i < arr.length - 1) {
						yt_url.append("&");
					}
				}
				if (itag != 0) {
					if (YtUtil.isNormalVideo(itag)) {
						Logger.log("Normal vid");
						return false;
					}
				}

				DASH_INFO info = new DASH_INFO();
				info.url = yt_url.toString();
				info.clen = clen;
				info.video = mime.startsWith("video");
				info.itag = itag;
				info.id = id;
				info.mime = mime;
				info.headers = data.getRequestHeaders();

				Logger.log("processing yt mime: " + mime + " id: " + id + " clen: " + clen + " itag: " + itag);

				if (YtUtil.addToQueue(info)) {
					DASH_INFO di = YtUtil.getDASHPair(info);

					if (di != null) {
						DashMetadata dm = new DashMetadata();
						dm.setUrl(info.video ? info.url : di.url);
						dm.setUrl2(info.video ? di.url : info.url);
						dm.setLen1(info.video ? info.clen : di.clen);
						dm.setLen2(info.video ? di.clen : info.clen);
						dm.setHeaders(info.video ? info.headers : di.headers);
						dm.setHeaders2(info.video ? di.headers : info.headers);
						String file = data.getFile();
						if (StringUtils.isNullOrEmptyOrBlank(file)) {
							file = XDMUtils.getFileName(data.getUrl());
						} else {
							file = XDMUtils.createSafeFileName(file);
						}
						Logger.log("file: " + file + " url1: " + dm.getUrl() + " url2: " + dm.getUrl2() + " len1: "
								+ dm.getLen1() + " len2: " + dm.getLen2());

						String szStr = null;
						if (info.clen > 0 && di.clen > 0) {
							szStr = FormatUtilities.formatSize(info.clen + di.clen);
						}

						String videoContentType = info.video ? info.mime : di.mime;
						String audioContentType = di.video ? info.mime : di.mime;

						String ext = getYtDashFormat(videoContentType, audioContentType);
						file += "." + ext;

						XDMApp.getInstance().addMedia(dm, file, YtUtil.getInfoFromITAG(info.video ? info.itag : di.itag)
								+ (szStr == null ? "" : " " + szStr));
						return true;
					}
				} else {
					System.out.println("+++updating");
					// sometimes dash segments are available, but the title of the page is not
					// properly updated yet
					// update existing video name when ever the tab title changes
					XDMApp.getInstance().youtubeVideoTitleUpdated(info.url, data.getFile());
				}
				return true;
			}
		} catch (Exception e) {
			Logger.log(e);
		}
		return false;
	}

	private boolean processVideoManifest(ParsedHookData data) {
		String url = data.getUrl();
		// String file = data.getFile();
		String contentType = data.getContentType();
		if (contentType == null) {
			contentType = "";
		}
		String ext = XDMUtils.getExtension(XDMUtils.getFileName(data.getUrl()));
		File manifestfile = null;

		try {
			if (contentType.contains("mpegurl") || ".m3u8".equalsIgnoreCase(ext) || contentType.contains("m3u8")) {
				Logger.log("Downloading m3u8 manifest");
				manifestfile = downloadMenifest(data);
				return M3U8Handler.handle(manifestfile, data);
			}
			if (contentType.contains("f4m") || ".f4m".equalsIgnoreCase(ext)) {
				Logger.log("Downloading f4m manifest");
				manifestfile = downloadMenifest(data);
				return F4mHandler.handle(manifestfile, data);
			}
			if (url.contains(".facebook.com") && url.toLowerCase().contains("pagelet")) {
				Logger.log("Downloading fb manifest");
				manifestfile = downloadMenifest(data);
				return FBHandler.handle(manifestfile, data);
			}
			if (url.contains("player.vimeo.com") && contentType.toLowerCase().contains("json")) {
				Logger.log("Downloading video manifest");
				manifestfile = downloadMenifest(data);
				return VimeoHandler.handle(manifestfile, data);
			}
			if (url.contains("instagram.com/p/")) {
				Logger.log("Downloading video manifest");
				manifestfile = downloadMenifest(data);
				return InstagramHandler.handle(manifestfile, data);
			}
		} catch (Exception e) {
		} finally {
			if (manifestfile != null) {
				manifestfile.delete();
			}
		}

		return false;
	}

	private void processNormalVideo(ParsedHookData data) {
		String file = data.getFile();
		String type = data.getContentType();
		if (type == null) {
			type = "";
		}
		if (StringUtils.isNullOrEmptyOrBlank(file)) {
			file = XDMUtils.getFileName(data.getUrl());
		}
		String ext = "";
		if (type.contains("video/mp4")) {
			ext = "mp4";
		} else if (type.contains("video/x-flv")) {
			ext = "flv";
		} else if (type.contains("video/webm")) {
			ext = "mkv";
		} else if (type.contains("matroska") || type.contains("mkv")) {
			ext = "mkv";
		} else if (type.equals("audio/mpeg") || type.contains("audio/mp3")) {
			ext = "mp3";
		} else if (type.contains("audio/aac")) {
			ext = "aac";
		} else if (type.contains("audio/mp4")) {
			ext = "m4a";
		} else {
			return;
		}
		file += "." + ext;

		if (data.getContentLength() < Config.getInstance().getMinVidSize()) {
			Logger.log("video less than min size");
			return;
		}

		HttpMetadata metadata = new HttpMetadata();
		metadata.setUrl(data.getUrl());
		metadata.setHeaders(data.getRequestHeaders());
		metadata.setSize(data.getContentLength());
		long size = data.getContentLength();
		if (size > 0) {
			if (data.isPartialResponse()) {
				size = -1;
			}
		}
		String sz = (size > 0 ? FormatUtilities.formatSize(size) : "");
		if (ext.length() > 0) {
			sz += " " + ext.toUpperCase();
		}

		XDMApp.getInstance().addMedia(metadata, file, sz);
	}

	private File downloadMenifest(ParsedHookData data) {
		JavaHttpClient client = null;
		OutputStream out = null;
		try {
			Logger.log("downloading manifest: " + data.getUrl());
			client = new JavaHttpClient(data.getUrl());
			Iterator<HttpHeader> headers = data.getRequestHeaders().getAll();
			boolean hasAccept = false;
			while (headers.hasNext()) {
				HttpHeader header = headers.next();
				Logger.log(header.getName() + " " + header.getValue());
				if (header.getName().toLowerCase().equals("accept")) {
					hasAccept = true;
				}
				client.addHeader(header.getName(), header.getValue());
			}
			if (!hasAccept) {
				client.addHeader("Accept", "*");
			}
			client.setFollowRedirect(true);
			client.connect();
			int resp = client.getStatusCode();
			Logger.log("manifest download response: " + resp);
			if (resp == 206 || resp == 200) {
				InputStream in = client.getInputStream();
				File tmpFile = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
				long len = client.getContentLength();
				out = new FileOutputStream(tmpFile);
				XDMUtils.copyStream(in, out, len);
				Logger.log("manifest download successfull");

				return tmpFile;
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				out.close();
			} catch (Exception e) {
			}
			try {
				client.dispose();
			} catch (Exception e) {
			}
		}
		return null;
	}

	private String getYtDashFormat(String videoContentType, String audioContentType) {
		if (videoContentType == null) {
			videoContentType = "";
		}
		if (audioContentType == null) {
			audioContentType = "";
		}
		if (videoContentType.contains("mp4") && audioContentType.contains("mp4")) {
			return "mp4";
		} else {
			return "mkv";
		}
	}

}