package xdm.app

interface IQueueManager {
    fun attachToQueue(queueId: Long, downloads: List<Long>)
}

class QueueManager : IQueueManager {
    override fun attachToQueue(queueId: Long, downloads: List<Long>) {}
}
