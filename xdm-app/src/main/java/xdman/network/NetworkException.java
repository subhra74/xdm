package xdman.network;

import java.io.*;

@SuppressWarnings("serial")
public class NetworkException extends IOException {
	public NetworkException(String msg) {
		super(msg);
	}
}
