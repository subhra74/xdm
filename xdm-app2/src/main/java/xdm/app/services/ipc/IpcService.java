package xdm.app.services.ipc;

public interface IpcService {
  void start(Runnable onError, Runnable onSuccess);
}
