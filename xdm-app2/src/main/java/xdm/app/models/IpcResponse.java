package xdm.app.models;

import lombok.Data;
import xdm.core.network.http.HeaderCollection;

import java.io.IOException;
import java.io.OutputStream;

@Data
public class IpcResponse {
  private int code;
  private String message;
  private byte[] body;
  private HeaderCollection headers = new HeaderCollection();

  public void write(OutputStream out) throws IOException {
    var buf = new StringBuilder();
    buf.append("HTTP/1.1 ").append(code).append(" ").append(message).append("\r\n");
    if (code != 204) {
      headers.addHeader("Content-Length", String.valueOf(body == null ? 0 : body.length));
    }
    headers.appendToBuffer(buf);
    buf.append("\r\n");
    out.write(buf.toString().getBytes());
    if (code != 204 && body != null) {
      out.write(body);
    }
    out.flush();
  }
}
