package xdm.app.service.impl;

import xdm.app.service.RpcService;

import java.util.function.Consumer;

public class RpcServiceImpl implements RpcService {
  @Override
  public void start(Runnable onSuccess, Consumer<Throwable> onError) {
    onSuccess.run();
  }
}
