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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocket;

import org.tinylog.Logger;

import xdman.Config;
import xdman.network.http.HttpContext;

public class SocketFactory {

	public static SSLSocket wrapSSL(Socket socket, String host, int port) throws NetworkException {
		try {
			SSLSocket sock2 = (SSLSocket) (HttpContext.getInstance().getSSLContext().getSocketFactory())
					.createSocket(socket, host, port, true);
			sock2.startHandshake();
			return sock2;
		} catch (IOException e) {
			Logger.error(e);
			throw new NetworkException("Https connection failed: " + host + ":" + port);
		}
	}

	public static Socket createSocket(String host, int port) throws HostUnreachableException {
		Config config = Config.getInstance();
		try {
			Socket sock = new Socket();
			sock.setSoTimeout(Config.getInstance().getNetworkTimeout() * 1000);
			sock.setTcpNoDelay(true);
			if (config.getTcpWindowSize() > 0) {
				try {
					sock.setReceiveBufferSize(config.getTcpWindowSize() * 1024);
				} catch (Exception e) {
					Logger.error(e);
				}
			}
			Logger.info("Tcp RWin: " + sock.getReceiveBufferSize());
			sock.setSoLinger(false, 0);
			sock.connect(new InetSocketAddress(host, port));
			return sock;
		} catch (IOException e) {
			Logger.error(e);
			throw new HostUnreachableException("Unable to connect to: " + host + ":" + port);
		}
	}
}
