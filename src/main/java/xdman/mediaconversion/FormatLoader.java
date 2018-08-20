package xdman.mediaconversion;

import xdman.ui.res.StringResource;
import xdman.util.Logger;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FormatLoader {
	private static final String FORMAT_DB_FILE_NAME = "formats/format_db.txt";

	public static List<FormatGroup> load() {
		BufferedReader bufferedReader = null;
		List<FormatGroup> list = new ArrayList<>();
		try {
			InputStream inStream = StringResource.class.getResourceAsStream(String.format("/%s", FORMAT_DB_FILE_NAME));
			if (inStream == null) {
				inStream = new FileInputStream(FORMAT_DB_FILE_NAME);
			}
			InputStreamReader r = new InputStreamReader(inStream, StandardCharsets.UTF_8);
			bufferedReader = new BufferedReader(r);
			String ln;
			while ((ln = bufferedReader.readLine()) != null) {
				FormatGroup fg = new FormatGroup();
				String[] arr = ln.split("\\|");
				fg.name = arr[0].trim();
				fg.desc = arr[1].trim();
				Logger.log("group:", fg.name);
				list.add(fg);
			}
			Format format;
			while ((format = Format.read(bufferedReader)) != null) {
				print(format);
				for (FormatGroup fg : list) {
					if (fg.name.equals(format.group)) {
						Logger.log(fg.desc, format.desc);
						fg.formats.add(format);
					}
				}
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			if (bufferedReader != null) {
				try {
					bufferedReader.close();
				} catch (Exception e2) {
					Logger.log(e2);
				}
			}
		}
		return list;
	}

	static void print(Format format) {
		Logger.log("\t", format.getDesc(), "'", format.group, "'");
		List<String> list = format.getVideoCodecs();
		if (list.size() > 0) {
			System.out.print("\t\tVideo Codec:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefautVideoCodec())) {
						System.out.print("*");
					}

					System.out.print(list.get(i) + " ");
				}
			}
			Logger.log("\n");
		}

		list = format.getResolutions();
		if (list.size() > 0) {
			System.out.print("\t\tResolution:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).length() > 1) {
					if (list.get(i).equals(format.getDefaultResolution())) {
						System.out.print("*");
					}

					System.out.print(list.get(i) + " ");
				}
			}
			Logger.log("\n");
		}

		list = format.getAudioChannel();
		if (list.size() > 0) {
			System.out.print("\t\tChannel:");
			for (int i = 0; i < list.size(); i++) {
				if (list.get(i).equals(format.getDefaultAudioChannel())) {
					System.out.print("*");
				}

				System.out.print(list.get(i) + " ");
			}
			Logger.log("\n");
		}

	}
}
