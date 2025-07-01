package xdm.core.util;

import java.security.SecureRandom;

public final class UniqueID {
  private UniqueID() {}

  private static final SecureRandom idGen = new SecureRandom();

  public static synchronized long get() {
    return idGen.nextLong();
  }
}
