package xdman.util;

import java.io.PrintStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    public static void log(Object obj) {
        if (obj instanceof Throwable) {
            Throwable throwable = (Throwable) obj;
            log(System.err,
                    throwable);
        } else {
            log(System.out,
                    obj);
        }
    }

    private static void log(PrintStream printStream,
                            Object obj) {
        String currentThreadName = Thread.currentThread().getName();
        String timeStamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        printStream.printf("%s\t[ %s ]\t%s%n",
                timeStamp,
                currentThreadName,
                obj);
    }
}
