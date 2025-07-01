package xdman.util;

import java.io.*;

public class Logger {
  private static boolean isEnabled = false;
  private static PrintWriter logOut;
  private String className;

  public static Logger getLogger(Class<?> cls) {
    Logger logger = new Logger();
    logger.className = cls.getName();
    return logger;
  }

  public static synchronized void initConsoleLogging() {
    logOut = new PrintWriter(System.err, true);
    isEnabled = true;
  }

  public static synchronized void initFileLogging(String logFile, String directory) {
    try {
      logOut = new PrintWriter(new BufferedWriter(new FileWriter(new File(directory, logFile))));
      isEnabled = true;
    } catch (Exception ex) {
      // Nothing to do
    }
  }

  public void info(String formatStr, Object... args) {
    try {
      log("[" + this.className + "] " + String.format(formatStr, args));
    } catch (Exception ex) {
      // Nothing to do
      ex.printStackTrace();
    }
  }

  public void error(String msg, Throwable t) {
    log("[" + this.className + "] " + msg);
    log(t);
  }

  private static void log(String text) {
    if (isEnabled) {
      synchronized (Logger.class) {
        logOut.println(text);
      }
    }
  }

  private static void log(Throwable t) {
    if (isEnabled) {
      synchronized (Logger.class) {
        t.printStackTrace(logOut);
      }
    }
  }

  private static PrintStream getLogStream() {
    return System.out;
  }

  private static PrintStream getErrorStream() {
    return System.err;
  }

  public static void log(Object obj) {
    if (obj instanceof Throwable) {
      getErrorStream().print("[ " + Thread.currentThread().getName() + " ] ");
      ((Throwable) obj).printStackTrace(getErrorStream());
    } else {
      getLogStream().println("[ " + Thread.currentThread().getName() + " ] " + obj);
    }
  }
}
