package xdm.app.services.ipc.impl;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xdm.App;
import xdm.app.services.ipc.IpcService;

public class IpcServiceImpl implements IpcService {
  private static final Logger logger = LoggerFactory.getLogger(IpcServiceImpl.class);
  public static final IpcService INSTANCE = new IpcServiceImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private IpcServiceImpl() {}

  @SuppressWarnings("InfiniteLoopStatement")
  @Override
  public void start(Runnable onError, Runnable onSuccess) {
    try (var serverSock = new ServerSocket()) {
      serverSock.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 9614));
      if (onSuccess != null) {
        onSuccess.run();
      }
      // App.instanceStarted();
      while (true) {
        final var sock = serverSock.accept();
        IpcSession session = new IpcSession(sock, objectMapper);
        session.start();
      }
    } catch (Exception e) {
      logger.error("Another instance already running", e);
      if (onError != null) {
        onError.run();
      }
      // App.instanceAlreadyRunning();
    }
  }
}
