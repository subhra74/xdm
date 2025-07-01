package xdm.app.service;

import java.util.List;

public interface QueueService {
  void attachToQueue(long queueId, List<Long> downloads);
}
