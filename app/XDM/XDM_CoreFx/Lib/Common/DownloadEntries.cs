using Newtonsoft.Json;
using System;

namespace XDM.Core.Lib.Common
{
    public abstract class BaseDownloadEntry : IComparable
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public long Size { get; set; }
        public string TargetDir { get; set; }
        public DateTime DateAdded { get; set; }
        public string DownloadType { get; set; }
        public FileNameFetchMode FileNameFetchMode { get; set; }
        public string PrimaryUrl { get; set; }
        public string RefererUrl { get; set; }
        public AuthenticationInfo? Authentication { get; set; }
        public ProxyInfo? Proxy { get; set; }
        public int MaxSpeedLimitInKiB { get; set; }
        public int CompareTo(object obj)
        {
            if (obj == null) return 1;
            if (obj is BaseDownloadEntry other)
                return this.Name.CompareTo(other.Name);
            else
                throw new ArgumentException("Object is not a BaseDownloadEntry");
        }

        public override string ToString()
        {
            return Name ?? "";
        }
    }

    public class InProgressDownloadEntry
        : BaseDownloadEntry
    {
        public int Progress { get; set; }
        public DownloadStatus Status { get; set; }
        [JsonIgnore]
        public string? DownloadSpeed { get; set; }
        [JsonIgnore]
        public string? ETA { get; set; }

        //public static InProgressDownloadEntry Deserialize(int version, BinaryReader reader)
        //{
        //    if (version == 1)
        //    {
        //        return InProgressDownloadEntrySerializerV1.Deserialize(reader);
        //    }
        //    throw new InvalidDataException("Unsupported version: " + version);
        //}

        //public void Serialize(BinaryWriter w)
        //{
        //    InProgressDownloadEntrySerializerV1.Serialize(this, w);
        //}
    }

    public class FinishedDownloadEntry : BaseDownloadEntry
    {
        //public static FinishedDownloadEntry Deserialize(int version, BinaryReader reader)
        //{
        //    if (version == 1)
        //    {
        //        return FinishedDownloadEntrySerializerV1.Deserialize(reader);
        //    }
        //    throw new InvalidDataException("Unsupported version: " + version);
        //}

        //public void Serialize(BinaryWriter w)
        //{
        //    FinishedDownloadEntrySerializerV1.Serialize(this, w);
        //}
    }

    public enum DownloadStatus
    {
        Downloading, Stopped, Finished
    }

    //internal static class FinishedDownloadEntrySerializerV1
    //{
    //    public static void Serialize(FinishedDownloadEntry entry, BinaryWriter writer)
    //    {
    //        writer.Write(1);
    //        writer.Write(entry.Id);
    //        writer.Write(entry.Name);
    //        writer.Write(entry.DateAdded.ToBinary());
    //        writer.Write(entry.Size);
    //        writer.Write(entry.DownloadType);
    //        writer.Write(entry.TargetDir ?? string.Empty);
    //        writer.Write(entry.PrimaryUrl ?? string.Empty);
    //        writer.Write(entry.Authentication.HasValue);
    //        if (entry.Authentication.HasValue)
    //        {
    //            writer.Write(entry.Authentication.Value.UserName ?? string.Empty);
    //            writer.Write(entry.Authentication.Value.Password ?? string.Empty);
    //        }
    //        //ProxyInfoSerializer.Serialize(entry.Proxy, writer);
    //    }

    //    public static FinishedDownloadEntry Deserialize(BinaryReader reader)
    //    {
    //        //version is already read, and this version was loaded
    //        var entry = new FinishedDownloadEntry
    //        {
    //            Id = reader.ReadString(),
    //            Name = reader.ReadString(),
    //            DateAdded = DateTime.FromBinary(reader.ReadInt64()),
    //            Size = reader.ReadInt64(),
    //            DownloadType = reader.ReadString(),
    //            TargetDir = Helpers.ReadString(reader),
    //            PrimaryUrl = Helpers.ReadString(reader),
    //        };
    //        if (reader.ReadBoolean())
    //        {
    //            entry.Authentication = new AuthenticationInfo
    //            {
    //                UserName = Helpers.ReadString(reader),
    //                Password = Helpers.ReadString(reader)
    //            };
    //        }
    //        ProxyInfoSerializer.Deserialize(reader);
    //        return entry;
    //    }
    //}

    //internal static class InProgressDownloadEntrySerializerV1
    //{
    //    public static void Serialize(InProgressDownloadEntry entry, BinaryWriter writer)
    //    {
    //        writer.Write(1);
    //        writer.Write(entry.Id);
    //        writer.Write(entry.Name);
    //        writer.Write(entry.DateAdded.ToBinary());
    //        writer.Write(entry.DownloadType);
    //        writer.Write((int)entry.FileNameFetchMode);
    //        writer.Write(entry.MaxSpeedLimitInKiB);
    //        writer.Write(entry.Progress);
    //        writer.Write(entry.Size);
    //        writer.Write(entry.PrimaryUrl ?? string.Empty);
    //        writer.Write(entry.TargetDir ?? string.Empty);
    //        writer.Write(entry.RefererUrl ?? string.Empty);
    //        writer.Write(entry.Authentication.HasValue);
    //        if (entry.Authentication.HasValue)
    //        {
    //            writer.Write(entry.Authentication.Value.UserName ?? string.Empty);
    //            writer.Write(entry.Authentication.Value.Password ?? string.Empty);
    //        }
    //        //ProxyInfoSerializer.Serialize(entry.Proxy, writer);
    //    }

    //    public static InProgressDownloadEntry Deserialize(BinaryReader reader)
    //    {
    //        //version is already read, and this version was loaded
    //        var entry = new InProgressDownloadEntry
    //        {
    //            Id = reader.ReadString(),
    //            Name = reader.ReadString(),
    //            DateAdded = DateTime.FromBinary(reader.ReadInt64()),
    //            DownloadType = reader.ReadString(),
    //            FileNameFetchMode = (FileNameFetchMode)reader.ReadInt32(),
    //            MaxSpeedLimitInKiB = reader.ReadInt32(),
    //            Progress = reader.ReadInt32(),
    //            Size = reader.ReadInt64(),
    //            PrimaryUrl = Helpers.ReadString(reader),
    //            TargetDir = Helpers.ReadString(reader),
    //            RefererUrl = Helpers.ReadString(reader),
    //        };
    //        if (reader.ReadBoolean())
    //        {
    //            entry.Authentication = new AuthenticationInfo
    //            {
    //                UserName = Helpers.ReadString(reader),
    //                Password = Helpers.ReadString(reader)
    //            };
    //        }
    //        ProxyInfoSerializer.Deserialize(reader);
    //        entry.Status = DownloadStatus.Stopped;
    //        return entry;
    //    }
    //}
}