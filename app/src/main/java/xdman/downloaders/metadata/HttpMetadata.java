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

package xdman.downloaders.metadata;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.Iterator;
import java.util.UUID;

import org.tinylog.Logger;

import xdman.Config;
import xdman.XDMConstants;
import xdman.network.http.HeaderCollection;
import xdman.network.http.HttpHeader;
import xdman.util.IOUtils;
import xdman.util.StringUtils;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class HttpMetadata {

	protected String id;
	protected String url;
	protected HeaderCollection headers;
	private long size;
	private String ydlUrl;

	public HttpMetadata derive() {
		Logger.info("derive normal metadata");
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

	public static HttpMetadata load(String id) {
		Logger.info("loading metadata: " + id);
		BufferedReader br = null;
		HttpMetadata metadata = null;
		int type;
		try {
			br = new BufferedReader(new FileReader(new File(Config.getInstance().getMetadataFolder(), id)));
			String ln = br.readLine();
			if (ln == null) {
				Logger.warn("invalid metadata, file is empty");
				return null;
			}
			int index = ln.indexOf(":");
			if (index < 0) {
				Logger.warn("invalid metadata file starting with: " + ln);
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
				Logger.warn("invalid metadata file starting with: " + ln);
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
				if (key.equals("url") && metadata != null) {
					metadata.setUrl(val);
				}
				if (key.equals("size") && metadata != null) {
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
				if (key.equals("url2") && metadata != null) {
					((DashMetadata) metadata).setUrl2(val);
				}
				if (key.equals("len1") && metadata != null) {
					((DashMetadata) metadata).setLen1(Long.parseLong(val));
				}
				if (key.equals("len2") && metadata != null) {
					((DashMetadata) metadata).setLen2(Long.parseLong(val));
				}
				if (key.equals("bitrate") && metadata != null) {
					((HdsMetadata) metadata).setBitRate(Integer.parseInt(val));
				}
				if (key.equals("ydlurl")) {
					Logger.info("ydurl: " + val);
					if (metadata != null) {
						metadata.ydlUrl = val;
					}
				}
			}
			IOUtils.closeFlow(br);
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(br);
		}
		return metadata;

	}

	public void save() {
		FileOutputStream fw = null;
		try {
			StringBuilder sb = new StringBuilder();
			if (url == null)
				throw new NullPointerException("url is null");
			sb.append("type: ").append(getType()).append("\n");
			sb.append("url: ").append(url).append("\n");
			sb.append("size: ").append(size).append("\n");
			if (headers != null) {
				Iterator<HttpHeader> headerIterator = headers.getAll();
				while (headerIterator.hasNext()) {
					HttpHeader header = headerIterator.next();
					sb.append("header: ").append(header.getName()).append(":").append(header.getValue()).append("\n");
				}
			}
			if (getType() == XDMConstants.HDS) {
				sb.append("bitrate: ").append(((HdsMetadata) this).getBitRate()).append("\n");
			}
			if (getType() == XDMConstants.DASH) {
				sb.append("url2: ").append(((DashMetadata) this).getUrl2()).append("\n");
				sb.append("len1: ").append(((DashMetadata) this).getLen1()).append("\n");
				sb.append("len2: ").append(((DashMetadata) this).getLen2()).append("\n");
				if (((DashMetadata) this).getHeaders2() != null) {
					Iterator<HttpHeader> headerIterator = ((DashMetadata) this).getHeaders2().getAll();
					while (headerIterator.hasNext()) {
						HttpHeader header = headerIterator.next();
						sb.append("header2: ").append(header.getName()).append(":").append(header.getValue()).append("\n");
					}
				}

			}
			if (!StringUtils.isNullOrEmptyOrBlank(ydlUrl)) {
				sb.append("ydlUrl: ").append(ydlUrl);
			}

			File metadataFolder = new File(Config.getInstance().getMetadataFolder());
			if (!metadataFolder.exists()) {
				metadataFolder.mkdirs();
			}
			File file = new File(metadataFolder, id);
			fw = new FileOutputStream(file);
			fw.write(sb.toString().getBytes());
			IOUtils.closeFlow(fw);
		} catch (Exception e) {
			Logger.error(e);
			IOUtils.closeFlow(fw);
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
