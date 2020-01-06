package xdman.network.http;

public class HttpHeader {

	public static HttpHeader parse(String str) {
		int index = str.indexOf(":");
		if (index < 0)
			return null;
		String key = str.substring(0, index);
		String val = str.substring(index + 1);
		return new HttpHeader(key, val);
	}

	public HttpHeader() {

	}

	public HttpHeader(String name, String value) {
		super();
		this.name = name;
		this.value = value;
	}

	private String name, value;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
