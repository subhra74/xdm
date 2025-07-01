package xdm.app.misc;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SleepBlocker {
  private AtomicBoolean active = new AtomicBoolean(false);

  public boolean isActive() {
    return active.get();
  }

  public void start() {
    // need to implement
  }

  public void stop() {
    // need to implement
  }
}
