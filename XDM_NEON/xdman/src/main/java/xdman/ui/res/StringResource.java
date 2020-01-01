package xdman.ui.res;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
		InputStream inStream = null;
		try {
			inStream = StringResource.class.getResourceAsStream("/lang/" + code + ".txt");
			if (inStream == null) {
				inStream = new FileInputStream("lang/" + code + ".txt");
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			prop.load(r);
			return true;
		} catch (Exception e) {
			Logger.log(e);
			return false;
		} finally {
			try {
				if (inStream != null) inStream.close();
			} catch (IOException e){
				Logger.log(e);
			}
		}
	}

}
