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

package xdman.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Map;

import javax.swing.JOptionPane;

import org.tinylog.Logger;

public class ParamUtils {

	public static void sendParam(Map<String, String> params) {
		StringBuilder sb = new StringBuilder();
		for (String key : params.keySet()) {
			String value = params.get(key);
			sb.append(key).append(":").append(value).append("\n");
		}

		StringBuilder reqBuf = new StringBuilder();
		reqBuf.append("GET /cmd HTTP/1.1\r\n");
		reqBuf.append("Content-Length: ").append(sb.length()).append("\r\n");
		reqBuf.append("Host: ").append(InetAddress.getLoopbackAddress().getHostName()).append("\r\n");
		reqBuf.append("Connection: close\r\n\r\n");
		reqBuf.append(sb);
		String resp = null;
		Socket sock = null;
		try {
			sock = new Socket(InetAddress.getLoopbackAddress(), 9614);
			InputStream in = sock.getInputStream();
			OutputStream out = sock.getOutputStream();
			out.write(reqBuf.toString().getBytes());
			resp = NetUtils.readLine(in);
			resp = resp.split(" ")[1];
		} catch (Exception e) {
			Logger.error(e);
		} finally {
			IOUtils.closeFlow(sock);
		}

		if (!"200".equals(resp)) {
			JOptionPane.showMessageDialog(null, "An older version of XDM is already running.");
		}
	}
}
