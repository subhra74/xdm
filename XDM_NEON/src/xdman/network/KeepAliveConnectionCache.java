package xdman.network;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;

public class KeepAliveConnectionCache implements Runnable {
	private ArrayList<KeepAliveInfo> socketList;
	private boolean stop;
	private final int MAX_KEEP_ALIVE_INT = 2000;
	private Thread t;
	private static KeepAliveConnectionCache _this;
	private static Object lock = new Object();

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
		this.socketList = new ArrayList<KeepAliveInfo>();
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

		ArrayList<Socket> sockets2Close = new ArrayList<Socket>();

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
				try {
					info.getSocket().close();
				} catch (IOException e) {
					e.printStackTrace();
				}
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
					e.printStackTrace();
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
