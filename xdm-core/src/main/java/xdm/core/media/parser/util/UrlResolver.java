package xdm.core.media.parser.util;

import java.net.URI;

public final class UrlResolver {
  public static URI resolve(URI baseUrl, String url) {
    return baseUrl.resolve(url.replace("\"", "").replace("'", ""));
  }
}
