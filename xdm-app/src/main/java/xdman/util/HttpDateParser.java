package xdman.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class HttpDateParser {
	private static SimpleDateFormat fmt;

	public synchronized static Date parseHttpDate(String lastModified) {
		if (StringUtils.isNullOrEmptyOrBlank(lastModified)) {
			return null;
		}
		if (fmt == null) {
			fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
			fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
		}
		try {
			return fmt.parse(lastModified);
		} catch (ParseException e) {
			Logger.log(e);
		}
		return null;
	}
}
