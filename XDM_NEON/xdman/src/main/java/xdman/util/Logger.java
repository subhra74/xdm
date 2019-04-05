package xdman.util;

import java.io.PrintStream;

public class Logger {
	private static PrintStream getLogStream() {
		return System.out;
	}

	private static PrintStream getErrorStream() {
		return System.err;
	}

	public static void log(Object obj) {
		if (obj instanceof Throwable) {
			getErrorStream().print(
					"[ " + Thread.currentThread().getName() + " ] ");
			((Throwable) obj).printStackTrace(getErrorStream());
		} else {
			getLogStream().println(
					"[ " + Thread.currentThread().getName() + " ] " + obj);
		}
	}
}
