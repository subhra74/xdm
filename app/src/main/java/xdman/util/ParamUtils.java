package xdman.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Iterator;
import java.util.Map;

import javax.swing.JOptionPane;

public class ParamUtils {
	public static void sendParam(Map<String, String> params) {
		StringBuffer sb = new StringBuffer();
		Iterator<String> paramIter = params.keySet().iterator();
		while (paramIter.hasNext()) {
			String key = paramIter.next();
			String value = params.get(key);
			sb.append(key + ":" + value + "\n");
		}

		InetAddress addr = InetAddress.getLoopbackAddress();

		StringBuffer reqBuf = new StringBuffer();
		reqBuf.append("GET /cmd HTTP/1.1\r\n");
		reqBuf.append("Content-Length: " + sb.length() + "\r\n");
		reqBuf.append("Host: " + addr.getHostName() + "\r\n");
		reqBuf.append("Connection: close\r\n\r\n");
		reqBuf.append(sb);
		String resp = null;
		Socket sock = null;
		try {
			sock = new Socket(InetAddress.getLoopbackAddress(), 9614);
			InputStream in = sock.getInputStream();
			OutputStream out = sock.getOutputStream();
			out.write(reqBuf.toString().getBytes());
			resp = NetUtils.readLine(in);
			resp = resp.split(" ")[1];
		} catch (Exception e) {
		} finally {
			if (sock != null) {
				try {
					sock.close();
				} catch (Exception e2) {
				}
			}
		}

		if (!"200".equals(resp)) {
			JOptionPane.showMessageDialog(null, "An older version of XDM is already running.");
		}
	}
}
