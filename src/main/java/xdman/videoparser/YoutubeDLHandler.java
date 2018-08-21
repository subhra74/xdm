package xdman.videoparser;

import xdman.Config;
import xdman.network.ProxyResolver;
import xdman.network.http.WebProxy;
import xdman.util.Logger;
import xdman.util.StringUtils;
import xdman.util.XDMUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class YoutubeDLHandler {
	private Process proc;
	private int exitCode;
	private String url;
	private File youTubeDLFile;
	private boolean stop;
	private String user, pass;

	private ArrayList<YdlResponse.YdlVideo> videos;

	public YoutubeDLHandler(String url, String user, String pass) {
		this.url = url;
		this.videos = new ArrayList<>();
		this.youTubeDLFile = getYouTubeDLFile();
		this.user = user;
		this.pass = pass;
	}


	public static boolean isYouTubeDLInstalled() {
		File youTubeDLFile = getYouTubeDLFile();
		boolean isYouTubeDLInstalled = isYouTubeDLInstalled(youTubeDLFile);
		return isYouTubeDLInstalled;
	}

	public static boolean isYouTubeDLInstalled(File youTubeDLFile) {
		boolean isYouTubeDLInstalled = youTubeDLFile != null
				&& youTubeDLFile.exists();
		Logger.log("is YouTube Downloader Installed",
				youTubeDLFile != null
						? youTubeDLFile.getAbsolutePath()
						: null,
				isYouTubeDLInstalled);
		return isYouTubeDLInstalled;
	}

	public static File getYouTubeDLFile() {
		String youTubeDL = getYouTubeDL();
		File dataYouTubeDLFile = new File(Config.getInstance().getDataFolder(),
				youTubeDL);
		if (dataYouTubeDLFile.exists()) {
			return dataYouTubeDLFile;
		}
		File jarYouTubeDLFile = new File(XDMUtils.getJarFile().getParentFile(),
				youTubeDL);
		File youTubeDLFile = jarYouTubeDLFile.exists()
				? jarYouTubeDLFile
				: null;
		return youTubeDLFile;
	}

	public static String getYouTubeDL() {
		String youTubeDL = XDMUtils.getEXEFileName("youtube-dl");
		return youTubeDL;
	}

	public void start() {
		if (!isYouTubeDLInstalled(youTubeDLFile)) {
			IOException ioException = new IOException(String.format("YouTube Downloader not installed %s",
					youTubeDLFile != null
							? youTubeDLFile.getAbsolutePath()
							: null));
			Logger.log(ioException);
			return;
		}
		File tmpError = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		File tmpOutput = new File(Config.getInstance().getTemporaryFolder(), UUID.randomUUID().toString());
		InputStream in = null;
		try {
			List<String> args = new ArrayList<String>();
			args.add(youTubeDLFile.getAbsolutePath());
			args.add("--no-warnings");
			args.add("-q");
			args.add("-i");
			args.add("-J");
			if (!(StringUtils.isNullOrEmptyOrBlank(user) || StringUtils.isNullOrEmptyOrBlank(pass))) {
				args.add("-u");
				args.add(user);
				args.add("-p");
				args.add(pass);
			}

			WebProxy webproxy = ProxyResolver.resolve(url);
			if (webproxy != null) {
				StringBuilder sb = new StringBuilder();
				String user = Config.getInstance().getProxyUser();
				String pass = Config.getInstance().getProxyPass();
				if (!(StringUtils.isNullOrEmptyOrBlank(user)
						|| StringUtils.isNullOrEmptyOrBlank(pass))) {
					sb.append(user).append(":").append(pass);
				}
				String proxy = String.format("http://%s", webproxy.getHost());
				int port = webproxy.getPort();
				if (port > 0 && port != 80) {
					sb.append(":" + port);
				}
				if (sb.length() > 0) {
					sb.append("@");
				}
				sb.append(proxy);
				args.add("--proxy");
				args.add(sb.toString());
			}

			// args.add("--proxy");
			// args.add("http://127.0.0.1:8888");
			args.add(url);

			ProcessBuilder pb = new ProcessBuilder(args);
			for (int i = 0; i < args.size(); i++) {
				Logger.log(args.get(i));
			}

			Logger.log("Writing JSON to:", tmpOutput);

			pb.redirectError(tmpError);
			pb.redirectOutput(tmpOutput);
			proc = pb.start();

			// InputStream in = proc.getInputStream();
			// byte[] buf = new byte[8192];
			// while (true) {
			// int x = in.read(buf);
			// if (x == -1)
			// break;
			// bout.write(buf, 0, x);
			// }

			// OutputStream out = new FileOutputStream("test.txt");
			// out.write(bout.toByteArray());
			// out.close();

			// BufferedReader br=new BufferedReader(new InputStreamReader(new
			// FileInputStream(tmpOutput)));
			// StringBuilder json=new StringBuilder();
			// while(true) {
			// String ln=br.readLine();
			// if(ln==null)break;
			// json.append(ln+"\n");
			// }
			// br.close();
			// //String json = new String(bout.toByteArray());
			// Logger.log("----json end: " , json);
			exitCode = proc.waitFor();
			if (!stop) {
				in = new FileInputStream(tmpOutput);
				videos.addAll(YdlResponse.parse(in));
				Logger.log("video found: " + videos.size());
			}
		} catch (Exception e) {
			Logger.log(e);
		} finally {
			try {
				if (in != null) {
					in.close();
				}
			} catch (Exception e) {

			}
			tmpError.delete();
			tmpOutput.delete();
		}
	}

	public int getExitCode() {
		return exitCode;
	}

	public void setExitCode(int exitCode) {
		this.exitCode = exitCode;
	}

	public ArrayList<YdlResponse.YdlVideo> getVideos() {
		return videos;
	}

	public void stop() {
		try {
			proc.destroy();
		} catch (Exception e) {
			Logger.log(e);
		}
	}
}
