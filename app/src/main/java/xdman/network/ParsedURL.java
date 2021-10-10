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

package xdman.network;

import java.net.URL;

import org.tinylog.Logger;

import xdman.util.StringUtils;

public class ParsedURL {

	private String _url;
	private int port;
	private String host;
	private String pathAndQuery;
	private String protocol;

	public static ParsedURL parse(String urlString) {
		try {
			URL url = new URL(urlString);
			ParsedURL parsedURL = new ParsedURL();
			parsedURL._url = urlString;
			parsedURL.host = url.getHost();
			parsedURL.port = url.getPort();
			if (parsedURL.port < 0) {
				parsedURL.port = url.getDefaultPort();
			}
			parsedURL.protocol = url.getProtocol();
			parsedURL.pathAndQuery = url.getPath();
			if (StringUtils.isNullOrEmptyOrBlank(parsedURL.pathAndQuery)) {
				parsedURL.pathAndQuery = "/";
			}
			String query = url.getQuery();
			if (!StringUtils.isNullOrEmptyOrBlank(query)) {
				parsedURL.pathAndQuery += "?" + query;
			}
			return parsedURL;
		} catch (Exception e) {
			Logger.error(e);
			return null;
		}
	}

	@Override
	public String toString() {
		return _url;
	}

	public int getPort() {
		return port;
	}

	public String getHost() {
		return host;
	}

	public String getPathAndQuery() {
		return pathAndQuery;
	}

	public String getProtocol() {
		return protocol;
	}

}
