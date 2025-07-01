package xdm.core.network.http;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Range {
  private long start;
  private long end;
}
