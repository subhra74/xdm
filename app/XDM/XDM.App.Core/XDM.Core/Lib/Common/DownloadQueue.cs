using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Common
{
    public class DownloadQueue
    {
        public string ID { get; set; }
        public string Name { get; set; }
        public List<string> DownloadIds { get; set; } = new(0);
        public DownloadSchedule? Schedule { get; set; }

        public DownloadQueue(string id, string name)
        {
            this.ID = id;
            this.Name = name;
        }

        public static DownloadQueue Deserialize(int version, BinaryReader reader)
        {
            if (version == 1)
            {
                return QueueSerializerV1.Deserialize(reader);
            }
            throw new InvalidDataException("Unsupported version: " + version);
        }

        public void Serialize(BinaryWriter writer)
        {
            QueueSerializerV1.Serialize(this, writer);
        }

        public override string ToString() => this.Name;
    }

    internal static class QueueSerializerV1
    {
        public static void Serialize(DownloadQueue queue, BinaryWriter writer)
        {
            writer.Write(1);
            writer.Write(queue.ID);
            writer.Write(queue.Name);
            writer.Write(queue.DownloadIds.Count);
            foreach (var downloadId in queue.DownloadIds)
            {
                writer.Write(downloadId);
            }
            writer.Write(queue.Schedule.HasValue);
            if (queue.Schedule.HasValue)
            {
                writer.Write(queue.Schedule.Value.StartTime.Ticks);
                writer.Write(queue.Schedule.Value.EndTime.Ticks);
                writer.Write((int)queue.Schedule.Value.Days);
            }
        }

        public static DownloadQueue Deserialize(BinaryReader reader)
        {
            //version is already read, and this version was loaded
            var queue = new DownloadQueue(reader.ReadString(), reader.ReadString());
            var count = reader.ReadInt32();
            queue.DownloadIds = new List<string>(count);
            for (var i = 0; i < count; i++)
            {
                queue.DownloadIds.Add(reader.ReadString());
            }
            if (reader.ReadBoolean())
            {
                queue.Schedule = new DownloadSchedule
                {
                    StartTime = TimeSpan.FromTicks(reader.ReadInt64()),
                    EndTime = TimeSpan.FromTicks(reader.ReadInt64()),
                    Days = (WeekDays)reader.ReadInt32()
                };
            }
            return queue;
        }
    }
}
