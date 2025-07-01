package xdm.app.service;

import java.util.function.Consumer;

public interface RpcService {
  public void start(Runnable onSuccess, Consumer<Throwable> onError);
}
