package xdm.core.util;

import java.io.PrintStream;

public class Logger {
  private static PrintStream getLogStream() {
    return System.out;
  }

  private static PrintStream getErrorStream() {
    return System.err;
  }

  public static void info(String tag, String msg) {
    getLogStream().println("[ " + tag + " ] " + msg);
  }

  public static void error(String tag, String msg) {
    error(tag, msg, null);
  }

  public static void error(String tag, String msg, Throwable ex) {
    getErrorStream().println("[ " + tag + " ] " + msg);
    if (ex != null) {
      ex.printStackTrace(getErrorStream());
    }
  }

  public static void info(Object obj) {
    if (obj instanceof Throwable) {
      getErrorStream().print("[ " + Thread.currentThread().getName() + " ] ");
      ((Throwable) obj).printStackTrace(getErrorStream());
    } else {
      getLogStream().println("[ " + Thread.currentThread().getName() + " ] " + obj);
    }
  }

  public static void error(String msg) {
    getErrorStream().println("[ " + Thread.currentThread().getName() + " ] " + msg);
  }

  public static void error(Throwable t) {
    getErrorStream().print("[ " + Thread.currentThread().getName() + " ] ");
    t.printStackTrace(getErrorStream());
  }

  public static void error(String msg, Throwable t) {
    getErrorStream().println("[ " + Thread.currentThread().getName() + " ] " + msg);
    getErrorStream().print("[ " + Thread.currentThread().getName() + " ] ");
    t.printStackTrace(getErrorStream());
  }
}
