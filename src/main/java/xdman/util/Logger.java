package xdman.util;

import xdman.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

public class Logger {
    public static File logFile = new File(Config.getInstance().getTemporaryFolder(),
            "XDM.log");

    public static void log(Object... objects) {
        String line = getLine(objects);
        PrintStream printStream = getPrintStream(objects);
        log(printStream,
                line);
        log(logFile,
                line);
    }

    private static String getLine(Object... objects) {
        String newLine = System.getProperty("line.separator");
        StringBuilder logFormatStringBuilder = new StringBuilder("%s\t[ %s ]\t");
        Object[] logObjects = new Object[objects.length + 2];
        int logObjectIndex = 0;
        String timeStamp = DateTimeUtils.getLoggingTimeStamp();
        logObjects[logObjectIndex] = timeStamp;
        String currentThreadName = Thread.currentThread().getName();
        logObjectIndex++;
        logObjects[logObjectIndex] = currentThreadName;
        for (Object object : objects) {
            logFormatStringBuilder.append(" %s");
            logObjectIndex++;
            if (object instanceof Throwable) {
                Throwable throwable = (Throwable) object;
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(throwable.toString()).append(newLine);
                StackTraceElement[] stackTraceElements = throwable.getStackTrace();
                for (StackTraceElement stackTraceElement : stackTraceElements) {
                    stringBuilder.append(stackTraceElement.toString()).append(newLine);
                }
                logObjects[logObjectIndex] = stringBuilder.toString();
            } else {
                logObjects[logObjectIndex] = object.toString();
            }
        }
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

}
