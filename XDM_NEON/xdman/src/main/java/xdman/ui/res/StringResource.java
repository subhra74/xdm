package xdman.ui.res;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Properties;

import xdman.Config;
import xdman.util.Logger;

public class StringResource {
	private static Properties strings;

	// each file must have name like de.deutsch.german.txt

	public synchronized static String get(String id) {
		if (strings == null) {
			strings = new Properties();
			try {
				String lang = Config.getInstance().getLanguage();
				System.out.println(lang);
				if (!loadLang(lang, strings)) {
					Logger.log("Unable to load language: " + lang);
					strings.clear();
					loadLang("en", strings);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return strings.getProperty(id);
	}

	private static boolean loadLang(String code, Properties prop) {
		Logger.log("Loading language " + code);
		try {
			InputStream inStream = StringResource.class.getResourceAsStream("/lang/" + code + ".txt");
			if (inStream == null) {
				inStream = new FileInputStream("lang/" + code + ".txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, Charset.forName("utf-8"));
			prop.load(r);
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		}
	}

}
