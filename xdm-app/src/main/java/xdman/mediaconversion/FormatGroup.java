package xdman.mediaconversion;

import java.util.ArrayList;
import java.util.List;

public class FormatGroup {
	String name;
	String desc;
	List<Format> formats = new ArrayList<>();

	@Override
	public String toString() {
		return desc;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Format> getFormats() {
		return formats;
	}

	public void setFormats(List<Format> formats) {
		this.formats = formats;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}
}
