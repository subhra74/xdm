package xdman;

import xdman.util.Logger;

public class Main {
	static {
		System.setProperty("http.KeepAlive.remainingData", "0");
		System.setProperty("http.KeepAlive.queuedConnections", "0");
		System.setProperty("sun.net.http.errorstream.enableBuffering", "false");
		System.setProperty("awt.useSystemAAFontSettings", "lcd");
		System.setProperty("swing.aatext", "true");
		System.setProperty("sun.java2d.d3d", "false");
		System.setProperty("sun.java2d.opengl", "false");
		System.setProperty("sun.java2d.xrender", "false");
	}

	public static void main(String[] args) {
		Logger.log("loading...");
		Logger.log(System.getProperty("java.version")+" "+System.getProperty("os.version"));
		XDMApp.start(args);
	}

}
