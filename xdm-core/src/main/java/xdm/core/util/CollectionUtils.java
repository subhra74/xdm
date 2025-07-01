package xdm.core.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class CollectionUtils {
  @SafeVarargs
  public static <T> Set<T> setOf(T... values) {
    Set<T> set = new HashSet<>();
    Collections.addAll(set, values);
    return set;
  }
}
