package xdman.util;

public class StringUtils {
	public static boolean isNullOrEmpty(String str) {
		return str == null || str.length() < 1;
	}

	public static boolean isNullOrEmptyOrBlank(String str) {
		return str == null || str.trim().length() < 1;
	}

	public static byte[] getBytes(StringBuffer sb) {
		return sb.toString().getBytes();
	}

	public static byte[] getBytes(String s) {
		return s.getBytes();
	}
}
