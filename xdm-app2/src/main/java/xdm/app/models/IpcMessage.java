package xdm.app.models;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class IpcMessage {
    private String url;
    private String cookie;
    private Map<String, List<String>> requestHeaders;
    private Map<String, List<String>> responseHeaders;
    private String file;
    private String method;
    private String userAgent;
    private String tabUrl;
    private String tabId;
    private String tabTitle;
    private String referer;
    private long fileSize;
    private String mimeType;
    private String vid;
}
