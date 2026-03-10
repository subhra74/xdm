package xdm.app.service

interface QueueService {
    fun attachToQueue(queueId: Long, downloads: List<Long>)
}
