package xdman.downloaders.metadata;

import java.io.*;

import java.util.*;

import xdman.*;
import xdman.network.http.*;
import xdman.util.*;

public class HttpMetadata {
	protected String id;
	protected String url;
	protected HeaderCollection headers;
	private long size;
	private String ydlUrl;

	public HttpMetadata derive() {
		Logger.log("derive normal metadata");
		HttpMetadata md = new HttpMetadata();
		md.setHeaders(this.getHeaders());
		md.setUrl(this.getUrl());
		md.setSize(getSize());
		return md;
	}

	public HttpMetadata() {
		this.id = UUID.randomUUID().toString();
		headers = new HeaderCollection();
	}

	protected HttpMetadata(String id) {
		this.id = id;
		headers = new HeaderCollection();
	}

	public int getType() {
		if (url.startsWith("ftp")) {
			return XDMConstants.FTP;
		} else {
			return XDMConstants.HTTP;
		}
	}

	// public static HttpMetadata load(String id) {
	// BufferedReader br = null;
	// HttpMetadata metadata = null;
	//
	// try {
	// br = new BufferedReader(new FileReader(new
	// File(Config.getInstance().getMetadataFolder(), id)));
	// int type = Integer.parseInt(br.readLine());
	// switch (type) {
	// case XDMConstants.HTTP:
	// metadata = new HttpMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.HLS:
	// metadata = new HlsMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.HDS:
	// metadata = new HdsMetadata(id);
	// metadata.load(br);
	// break;
	// case XDMConstants.DASH:
	// metadata = new DashMetadata(id);
	// metadata.load(br);
	// break;
	// }
	//
	// br.close();
	// } catch (Exception e) {
	// Logger.log(e);
	// if (br != null) {
	// try {
	// br.close();
	// } catch (Exception ex) {
	// }
	// }
	// }
	// return metadata;
	// }

	public final String getUrl() {
		return url;
	}

	public final void setUrl(String url) {
		this.url = url;
	}

	public final HeaderCollection getHeaders() {
		return headers;
	}

	public final void setHeaders(HeaderCollection headers) {
		this.headers = headers;
	}

	public String getId() {
		return id;
	}

	// public void load(BufferedReader br) throws IOException {
	// url = br.readLine();
	// headers = new HeaderCollection();
	// while (true) {
	// String ln = br.readLine();
	// if (ln == null)
	// break;
	// HttpHeader header = HttpHeader.parse(ln);
	// if (header != null) {
	// headers.addHeader(header);
	// }
	// }
	// }

	public static HttpMetadata load(String id) {
		Logger.log("loading metadata: " + id);
		BufferedReader br = null;
		HttpMetadata metadata = null;
		int type;
		try {
			br = new BufferedReader(new FileReader(new File(Config.getInstance().getMetadataFolder(), id)));
			String ln = br.readLine();
			if (ln == null) {
				Logger.log("invalid metadata, file is empty");
				return null;
			}
			int index = ln.indexOf(":");
			if (index < 0) {
				Logger.log("invalid metadata file starting with: " + ln);
				return null;
			}
			String key = ln.substring(0, index).trim().toLowerCase();
			String val = ln.substring(index + 1).trim();
			if (key.equals("type")) {
				type = Integer.parseInt(val);
				if (type == XDMConstants.HTTP || type == XDMConstants.FTP) {
					metadata = new HttpMetadata(id);
				} else if (type == XDMConstants.HLS) {
					metadata = new HlsMetadata(id);
				} else if (type == XDMConstants.HDS) {
					metadata = new HdsMetadata(id);
				} else if (type == XDMConstants.DASH) {
					metadata = new DashMetadata(id);
				}
			} else {
				Logger.log("invalid metadata file starting with: " + ln);
				return null;
			}
			while (true) {
				ln = br.readLine();
				if (ln == null)
					break;
				index = ln.indexOf(":");
				if (index < 0)
					continue;
				key = ln.substring(0, index).trim().toLowerCase();
				val = ln.substring(index + 1).trim();
				if (key.equals("url")) {
					metadata.setUrl(val);
				}
				if (key.equals("size")) {
					metadata.setSize(Long.parseLong(val));
				}
				if (key.equals("header")) {
					int index2 = val.indexOf(":");
					if (index2 < 0) {
						continue;
					}
					String key1 = val.substring(0, index2).trim();
					String val1 = val.substring(index2 + 1).trim();
					metadata.headers.addHeader(key1, val1);
				}
				if (key.equals("header2")) {
					int index2 = val.indexOf(":");
					if (index2 < 0) {
						continue;
					}
					String key1 = val.substring(0, index2).trim();
					String val1 = val.substring(index2 + 1).trim();
					((DashMetadata) metadata).getHeaders2().addHeader(key1, val1);
				}
				if (key.equals("url2")) {
					((DashMetadata) metadata).setUrl2(val);
				}
				if (key.equals("len1")) {
					((DashMetadata) metadata).setLen1(Long.parseLong(val));
				}
				if (key.equals("len2")) {
					((DashMetadata) metadata).setLen2(Long.parseLong(val));
				}
				if (key.equals("bitrate")) {
					((HdsMetadata) metadata).setBitRate(Integer.parseInt(val));
				}
				if (key.equals("ydlurl")) {
					Logger.log("ydurl: " + val);
					metadata.ydlUrl = val;
				}
			}
			br.close();
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (Exception ex) {
				}
			}
		}
		return metadata;

	}

	public void save() {
		FileOutputStream fw = null;
		try {
			StringBuilder sb = new StringBuilder();
			if (url == null)
				throw new NullPointerException("url is null");
			sb.append("type: " + getType() + "\n");
			sb.append("url: " + url + "\n");
			sb.append("size: " + size + "\n");
			if (headers != null) {
				Iterator<HttpHeader> headerIterator = headers.getAll();
				while (headerIterator.hasNext()) {
					HttpHeader header = headerIterator.next();
					sb.append("header: " + header.getName() + ":" + header.getValue() + "\n");
				}
			}
			if (getType() == XDMConstants.HDS) {
				sb.append("bitrate: " + ((HdsMetadata) this).getBitRate() + "\n");
			}
			if (getType() == XDMConstants.DASH) {
				sb.append("url2: " + ((DashMetadata) this).getUrl2() + "\n");
				sb.append("len1: " + ((DashMetadata) this).getLen1() + "\n");
				sb.append("len2: " + ((DashMetadata) this).getLen2() + "\n");
				if (((DashMetadata) this).getHeaders2() != null) {
					Iterator<HttpHeader> headerIterator = ((DashMetadata) this).getHeaders2().getAll();
					while (headerIterator.hasNext()) {
						HttpHeader header = headerIterator.next();
						sb.append("header2: " + header.getName() + ":" + header.getValue() + "\n");
					}
				}

			}
			if (!StringUtils.isNullOrEmptyOrBlank(ydlUrl)) {
				sb.append("ydlUrl: " + ydlUrl);
			}

			File metadataFolder = new File(Config.getInstance().getMetadataFolder());
			if (!metadataFolder.exists()) {
				metadataFolder.mkdirs();
			}
			File file = new File(metadataFolder, id);
			fw = new FileOutputStream(file);
			fw.write(sb.toString().getBytes());
			fw.close();
		} catch (Exception e) {
			Logger.log(e);
			if (fw != null) {
				try {
					fw.close();
				} catch (Exception ex) {
				}
			}
		}
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public String getYdlUrl() {
		return ydlUrl;
	}

	public void setYdlUrl(String ydlUrl) {
		this.ydlUrl = ydlUrl;
	}
}
