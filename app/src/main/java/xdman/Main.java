package xdman;

import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;

import java.time.LocalDate;

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
		// Disable Java 9 Dpi scaling as XDM uses its own dpi scaling
		// System.setProperty("sun.java2d.uiScale.enabled", "true");
		// System.setProperty("sun.java2d.uiScale", "2.75");
	}

	private static void loadLoggerConfiguration() {
		String logFile = System.getProperty("user.home") + "/.xdman/logs/log-" + LocalDate.now() + ".log";
		Configuration.set("writer", "file");
		Configuration.set("writer.level ", "debug");
		Configuration.set("writer.file", logFile);
		Configuration.set("writer.charset", "UTF-8");
		Configuration.set("writer.append", "true");
	}

	public static void main(String[] args) {
		loadLoggerConfiguration();
		Logger.info("loading...");
		Logger.info(System.getProperty("java.version") + " " + System.getProperty("os.version"));
		XDMApp.start(args);
	}

}
