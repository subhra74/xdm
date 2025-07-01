package xdm.app.services.ipc.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.app.models.IpcResponse;
import xdm.monitoring.*;

public class IpcSession {
  private static final Logger logger = LoggerFactory.getLogger(IpcSession.class);
  private final Socket sock;
  private InputStream inStream;
  private OutputStream outStream;
  private final Request request;
  private final IpcResponse response;
  private final ObjectMapper objectMapper;

  public IpcSession(final Socket socket, final ObjectMapper objectMapper) {
    this.sock = socket;
    this.request = new Request();
    this.response = new IpcResponse();
    this.objectMapper = objectMapper;
  }

  public void start() {
    Thread t =
        new Thread(
            () -> {
              try {
                inStream = sock.getInputStream();
                outStream = sock.getOutputStream();
                while (true) {
                  this.request.read(inStream);
                  this.processRequest(this.request, this.response);
                  this.response.write(outStream);
                }
              } catch (Exception e) {
                logger.error(e.getMessage(), e);
              }
              cleanup();
            });
    t.setDaemon(true);
    t.start();
  }

  private void processRequest(Request request, IpcResponse res) throws IOException {
    var verb = request.getUrl();
    switch (verb) {
      case "/sync":
        break;
      case "/download":
        IpcHelper.onDownload(objectMapper, request, response);
        break;
      default:
        logger.info("Unknown directive: {}", verb);
        break;
    }
    IpcHelper.onSync(this.objectMapper, res);
  }

  private void cleanup() {
    try {
      inStream.close();
    } catch (Exception e) {
      // No op
    }

    try {
      outStream.close();
    } catch (Exception e) {
      // No op
    }

    try {
      sock.close();
    } catch (Exception e) {
      // No op
    }
  }
}
