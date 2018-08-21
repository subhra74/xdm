package xdman.network;

import java.io.IOException;

@SuppressWarnings("serial")
public class HostUnreachableException extends IOException {
	public HostUnreachableException() {

	}

	public HostUnreachableException(String msg) {
		super(msg);
	}
}
