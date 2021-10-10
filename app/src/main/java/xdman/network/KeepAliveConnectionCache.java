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

import java.net.Socket;
import java.util.ArrayList;

import org.tinylog.Logger;
import xdman.util.IOUtils;

public class KeepAliveConnectionCache implements Runnable {

	private final ArrayList<KeepAliveInfo> socketList;
	private boolean stop;
	private final int MAX_KEEP_ALIVE_INT = 2000;
	private Thread t;
	private static KeepAliveConnectionCache _this;
	private static final Object lock = new Object();

	public static KeepAliveConnectionCache getInstance() {
		synchronized (lock) {
			if (_this == null) {
				_this = new KeepAliveConnectionCache();
				_this.start();
			}
			return _this;
		}
	}

	private KeepAliveConnectionCache() {
		this.socketList = new ArrayList<>();
	}

	public synchronized void putSocket(Socket socket, String host, int port) {
		KeepAliveInfo info = new KeepAliveInfo();
		info.setLastUsed(System.currentTimeMillis());
		info.setHost(host);
		info.setPort(port);
		info.setSocket(socket);
		socketList.add(info);
	}

	public synchronized Socket getReusableSocket(String host, int port) {
		long now = System.currentTimeMillis();
		for (int i = 0; i < socketList.size(); i++) {
			KeepAliveInfo info = socketList.get(i);
			if (info.getHost().equals(host) && info.getPort() == port) {
				if (now - info.getLastUsed() < MAX_KEEP_ALIVE_INT) {
					socketList.remove(i);
					return info.getSocket();
				}
			}
		}
		return null;
	}

	private void scavengeCache() {

		ArrayList<Socket> sockets2Close = new ArrayList<>();

		synchronized (_this) {
			for (int i = 0; i < socketList.size(); i++) {
				KeepAliveInfo info = socketList.get(i);
				long now = System.currentTimeMillis();
				if (now - info.getLastUsed() >= MAX_KEEP_ALIVE_INT) {
					socketList.remove(i);
					sockets2Close.add(info.getSocket());
				}
			}
		}

		for (int i = 0; i < socketList.size(); i++) {
			KeepAliveInfo info = socketList.get(i);
			long now = System.currentTimeMillis();
			if (now - info.getLastUsed() >= MAX_KEEP_ALIVE_INT) {
				socketList.remove(i);
				IOUtils.closeFlow(info.getSocket());
			}
		}
	}

	@Override
	public void run() {
		while (!stop) {
			long lastrun = System.currentTimeMillis();
			scavengeCache();
			long now = System.currentTimeMillis();

			if (now - lastrun < MAX_KEEP_ALIVE_INT) {
				try {
					Thread.sleep(MAX_KEEP_ALIVE_INT - (now - lastrun));
				} catch (InterruptedException e) {
					Logger.error(e);
				}
			}
		}
	}

	public void start() {
		this.t = new Thread(this);
		t.start();
	}

	public void stop() {
		this.stop = true;
		if (this.t != null) {
			t.interrupt();
		}
	}

}
