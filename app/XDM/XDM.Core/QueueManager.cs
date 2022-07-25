using System;
using System.Collections.Generic;
using System.IO;
using TraceLog;
using XDM.Core;

namespace XDM.Core
{
    public static class QueueManager
    {
        private static List<DownloadQueue> queues =
            new List<DownloadQueue> { new DownloadQueue(Guid.NewGuid().ToString(), "Default queue") };
        public static int QueueAutoNumber { get; set; } = 1;

        public static DownloadQueue? GetQueue(string id)
        {
            foreach (var queue in queues)
            {
                if (id == queue.ID) return queue;
            }
            return null;
        }

        public static void Load()
        {
            lock (queues)
            {
                var queueFile = Path.Combine(Config.DataDir, "queues.db");
                if (File.Exists(queueFile))
                {
                    using var reader = new BinaryReader(new FileStream(queueFile, FileMode.Open));
                    try
                    {
                        QueueAutoNumber = reader.ReadInt32();
                        var count = reader.ReadInt32();
                        if (count > 0)
                        {
                            queues.Clear();
                        }
                        for (var i = 0; i < count; i++)
                        {
                            var version = reader.ReadInt32();
                            var queue = DownloadQueue.Deserialize(version, reader);
                            queues.Add(queue);
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Debug(ex, ex.Message);
                    }
                    if (queues.Count == 0)
                    {
                        queues.Add(new DownloadQueue(Guid.NewGuid().ToString(), "Default queue"));
                    }
                }
            }
        }

        public static void Save()
        {
            lock (queues)
            {
                var count = queues.Count;
                using var writer = new BinaryWriter(new FileStream(Path.Combine(Config.DataDir, "queues.db"), FileMode.Create));
                writer.Write(QueueAutoNumber);
                writer.Write(count);
                foreach (var queue in queues)
                {
                    queue.Serialize(writer);
                }
            }
        }

        public static IList<DownloadQueue> Queues => queues;

        public static void AddDownloadsToQueue(string queueId, string[] downloadIds)
        {
            var set = new HashSet<string>(downloadIds);
            foreach (var queue in queues)
            {
                var toRemove = new List<string>();
                foreach (var downloadId in queue.DownloadIds)
                {
                    if (set.Contains(downloadId))
                    {
                        toRemove.Add(downloadId);
                    }
                }
                foreach (var id in toRemove)
                {
                    queue.DownloadIds.Remove(id);
                }
            }

            foreach (var queue in queues)
            {
                if (queue.ID == queueId)
                {
                    foreach (var downloadId in downloadIds)
                    {
                        if (!queue.DownloadIds.Contains(downloadId))
                        {
                            queue.DownloadIds.Add(downloadId);
                        }
                    }
                    break;
                }
            }
            Save();
        }

        public static void RemoveFinishedDownload(string downloadId)
        {
            foreach (var queue in queues)
            {
                if (queue.DownloadIds.Contains(downloadId))
                {
                    queue.DownloadIds.Remove(downloadId);
                }
            }
        }
    }
}
