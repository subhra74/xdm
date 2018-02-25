package xdman.monitoring;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import xdman.XDMApp;
import xdman.util.Logger;

public class BrowserMonitor implements Runnable {
	private static BrowserMonitor _this;
	
	public static BrowserMonitor getInstance() {
		if (_this == null) {
			_this = new BrowserMonitor();
		}
		return _this;
	}

	public void startMonitoring() {
		Thread t = new Thread(this);
		t.start();
	}

	public void run() {
		ServerSocket serverSock = null;
		try {
			serverSock = new ServerSocket();
			serverSock.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 9614));
			XDMApp.instanceStarted();
			while (true) {
				Socket sock = serverSock.accept();
				MonitoringSession session = new MonitoringSession(sock);
				session.start();
			}
		} catch (Exception e) {
			Logger.log(e);
			XDMApp.instanceAlreadyRunning();
		}
		try {
			serverSock.close();
		} catch (Exception e) {
		}
	}
}
