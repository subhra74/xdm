package xdman.network;

import java.io.*;

@SuppressWarnings("serial")
public class HostUnreachableException extends IOException {
	public HostUnreachableException() {

	}

	public HostUnreachableException(String msg) {
		super(msg);
	}
}
