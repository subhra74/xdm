package xdman.util;

import xdman.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

public class Logger {
	public static final int MAX_THREAD_NAME_LENGTH = 35;
	private static File logFile = getLogFile();

	public static void log(Object... objects) {
		String line = getLine(objects);
		PrintStream printStream = getPrintStream(objects);
		log(printStream,
				line);
		log(logFile,
				line);
	}

	public static File getLogFile() {
		if (logFile == null) {
			logFile = new File(Config.getInstance().getTemporaryFolder(),
					"XDM.log");
		}
		return logFile;
	}

	private static String getLine(Object... objects) {
		String newLine = System.getProperty("line.separator");
		StringBuilder logFormatStringBuilder = new StringBuilder();
		Object[] logObjects = new Object[objects.length + 5];
		int logObjectIndex = 0;
		String timeStamp = DateTimeUtils.getLoggingTimeStamp();
		logObjects[logObjectIndex] = timeStamp;
		logFormatStringBuilder.append("%s\t");

		logObjectIndex++;
		int levelIndex = logObjectIndex;
		boolean hasThrowable = false;
		logFormatStringBuilder.append("%s\t");

		Thread currentThread = Thread.currentThread();
		String currentThreadGroupName = currentThread.getThreadGroup().getName();
		logObjectIndex++;
		logObjects[logObjectIndex] = currentThreadGroupName;
		logFormatStringBuilder.append("%s");

		String currentThreadId = currentThread.getId() + "";
		logObjectIndex++;
		logObjects[logObjectIndex] = currentThreadId;
		logFormatStringBuilder.append(" [%s]");

		String currentThreadName = currentThread.getName();
		logObjectIndex++;
		logObjects[logObjectIndex] = currentThreadName;
		logFormatStringBuilder.append(" %s");
		for (int pad = MAX_THREAD_NAME_LENGTH - currentThreadName.length(); pad > 0; pad--) {
			logFormatStringBuilder.append(" ");
		}

		logFormatStringBuilder.append("\t");
		for (Object object : objects) {
			logFormatStringBuilder.append(" %s");
			logObjectIndex++;
			if (object instanceof Throwable) {
				hasThrowable = true;
				Throwable throwable = (Throwable) object;
				StringBuilder stringBuilder = new StringBuilder();
				stringBuilder.append(throwable.toString()).append(newLine);
				StackTraceElement[] stackTraceElements = throwable.getStackTrace();
				for (StackTraceElement stackTraceElement : stackTraceElements) {
					stringBuilder.append(stackTraceElement.toString()).append(newLine);
				}
				logObjects[logObjectIndex] = stringBuilder.toString();
			} else {
				logObjects[logObjectIndex] = object == null
						? "" :
						object.toString();
			}
		}
		logObjects[levelIndex] = hasThrowable
				? "ERROR"
				: "INFO ";
		logFormatStringBuilder.append(newLine);
		String logFormat = logFormatStringBuilder.toString();
		String line = String.format(logFormat,
				logObjects);
		return line;
	}

	private static void log(File logFile,
	                        String line) {
		BufferedWriter bufferedWriter = null;
		try {
			bufferedWriter = XDMUtils.getBufferedWriter(logFile,
					true);
			log(bufferedWriter,
					line);
		} catch (IOException e) {
			log(System.err,
					e);
		}
		if (bufferedWriter != null) {
			try {
				bufferedWriter.flush();
				bufferedWriter.close();
			} catch (IOException e) {
				Logger.log(e);
			}
		}
	}


	private static void log(BufferedWriter bufferedWriter,
	                        String line) throws IOException {
		bufferedWriter.write(line);
	}


	private static PrintStream getPrintStream(Object... objects) {
		PrintStream printStream = System.out;
		for (Object object : objects) {
			if (object instanceof Throwable) {
				printStream = System.err;
				break;
			}
		}
		return printStream;
	}

	private static void log(PrintStream printStream,
	                        String line) {
		printStream.print(line);
	}

	public static void renameOldLog() {
		logFile = getLogFile();
		if (logFile.exists()) {
			logFile = XDMUtils.renameOldFile(logFile);
		}
	}
}
